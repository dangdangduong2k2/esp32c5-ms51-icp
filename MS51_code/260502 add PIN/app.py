import tkinter as tk
from tkinter import filedialog, messagebox
from tkinter import ttk
import subprocess
from intelhex import IntelHex
import os
from PIL import Image, ImageTk, ImageOps  # PIL is actually installed as Pillow
import sys
import tempfile
import shutil

class FlashToolGUI:
    EEPROM_DATA_SIZE = 217

    def __init__(self, root):
        self.root = root
        self.use_pass_option = self.ask_pass_option()
        self.root.title("Microcontroller Flash Tool")
        self.root.geometry("570x790")  # Increased height for PIN options
        self.root.resizable(False, False)

        self.tool_path = self.detect_tool_path()
        self.file_path = tk.StringVar()
        self.output_hex_name = tk.StringVar(value="output.hex")  # Default output name
        self.eeprom_entries = []
        self.multi_byte_entries = {}  # Track entries that need byte splitting
        self.EEPROM_START_ADDRESS = 0x7F01  # Skip init byte
        self.checkbox_vars = []  # Thêm list để lưu các BooleanVar của checkbox
        self.linked_range_fields = {}

        # Khởi tạo tooltips
        self.tooltips = {}

        self.lock_chip_var = tk.BooleanVar(value=True)  # Default checked

        # File selection frame
        file_frame = ttk.LabelFrame(root, text="File Selection")
        file_frame.pack(fill="x", padx=5, pady=5)
        
        # Make frame use grid weights
        file_frame.grid_columnconfigure(1, weight=1)
        
        # Input hex row
        tk.Label(file_frame, text="Hex File:", width=10).grid(row=0, column=0, padx=5, pady=5)
        tk.Entry(file_frame, textvariable=self.file_path).grid(row=0, column=1, sticky="ew", padx=5)
        browse_btn = ttk.Button(file_frame, text="Browse", command=self.browse_file, width=10)
        browse_btn.grid(row=0, column=2, padx=5)
        
        # Output hex row - match style with input row
        tk.Label(file_frame, text="Output Hex:", width=10).grid(row=1, column=0, padx=5, pady=5)
        tk.Entry(file_frame, textvariable=self.output_hex_name).grid(row=1, column=1, sticky="ew", padx=5)
        gen_btn = ttk.Button(file_frame, text="Generate", command=self.generate_hex, width=10)
        gen_btn.grid(row=1, column=2, padx=5)

        # Connect status row
        status_frame = ttk.Frame(file_frame)
        status_frame.grid(row=2, column=0, columnspan=3, sticky="e", padx=5, pady=(0,5))
        self.connect_status = tk.Label(status_frame, text="Status: Disconnected", fg="red")
        self.connect_status.pack(side="left")
        self.mcu_info = tk.Label(status_frame, text="")
        self.mcu_info.pack(side="left", padx=(5,0))

        # Control buttons frame
        btn_frame = ttk.Frame(root)
        btn_frame.pack(fill="x", padx=5, pady=5)
        
        # Add lock chip checkbox
        lock_chk = tk.Checkbutton(btn_frame, text="Lock chip", variable=self.lock_chip_var)
        lock_chk.pack(side="right", padx=5)
        
        # Left side buttons 
        self.flash_btn = ttk.Button(btn_frame, text="Flash", command=self.flash_microcontroller)
        self.flash_btn.pack(side="left", padx=5)
        self.flash_btn["state"] = "disabled"
        
        self.erase_btn = ttk.Button(btn_frame, text="Erase", command=self.erase_microcontroller) 
        self.erase_btn.pack(side="left", padx=5)
        self.erase_btn["state"] = "disabled"
        
        self.reset_btn = ttk.Button(btn_frame, text="Reset", command=self.reset_microcontroller)
        self.reset_btn.pack(side="left", padx=5)
        self.reset_btn["state"] = "disabled"
        
        # Fixed width spacer to create consistent spacing
        spacer = ttk.Frame(btn_frame, width=5)  # Width matches padx of other buttons
        spacer.pack(side="left")
        spacer.pack_propagate(False)  # Prevent the frame from shrinking
        
        # Right side buttons
        self.save_flash_btn = ttk.Button(btn_frame, text="Save & Flash", command=self.save_and_flash)
        self.save_flash_btn.pack(side="left", padx=5)
        self.save_flash_btn["state"] = "disabled"
        
        ttk.Button(btn_frame, text="Connect", command=self.connect_device).pack(side="left", padx=5)

        # EEPROM data frames
        left_frame = ttk.LabelFrame(root, text="Parameters Setup")
        left_frame.pack(side="left", fill="both", expand=True, padx=1, pady=1)

        right_frame = ttk.LabelFrame(root, text="Mode Setup") 
        right_frame.pack(side="left", fill="both", expand=True, padx=1, pady=1)

        # Configure columns
        left_frame.grid_columnconfigure(0, minsize=65)
        left_frame.grid_columnconfigure(1, minsize=30)
        right_frame.grid_columnconfigure(0, minsize=100)
        right_frame.grid_columnconfigure(1, minsize=100)

        self.eeprom_entries = []
        row_left = row_right = 0

        # Map EEPROM fields in exact struct order
        # 1. time[2][2] array
        self.run_cl_entry = self.add_entry_field(left_frame, row_left, "Chạy lạnh CL :", 1, is_uint32=True, default="5", align="w", min_val=1, max_val=999, 
                            tooltip="Thời gian chạy lạnh CL (1-999)")
        row_left += 1
        self.run_op_entry = self.add_entry_field(left_frame, row_left, "Chạy lạnh OP :", 1, is_uint32=True, default="5", align="w", min_val=1, max_val=999,
                            tooltip="Thời gian chạy lạnh OP (1-999)")
        row_left += 1
        self.defrost_cl_entry = self.add_entry_field(left_frame, row_left, "Xả đá CL :", 1, is_uint32=True, default="60", align="w", min_val=1, max_val=999,
                            tooltip="Thời gian xả đá CL (1-999)")
        row_left += 1
        self.defrost_op_entry = self.add_entry_field(left_frame, row_left, "Xả đá OP :", 1, is_uint32=True, default="6", align="w", min_val=1, max_val=999,
                            tooltip="Thời gian xả đá OP (1-999)")
        row_left += 1

        # 2. delayST, ST
        self.add_entry_field(left_frame, row_left, "Delay ST(100ms) :", 1, is_uint8=True, default="70", align="w", min_val=1, max_val=999,
                            tooltip="Thời gian delay relay (1-999, đơn vị 0.1s)")
        row_left += 1
        
        # Split ST into LED and LCD
        # ST LED field
        self.st_led_entry = self.add_entry_field(left_frame, row_left, "ST LED(100ms) :", 1, is_uint8=True, default="50", align="w", min_val=1, max_val=90,
                            tooltip="Thời gian bật relay LED (1-90, đơn vị 0.1s)")
        row_left += 1

        # ST LCD field
        tk.Label(left_frame, text="ST LCD(100ms) :").grid(row=row_left, column=0, padx=(1,1), pady=2, sticky="w")
        frame_st_lcd = ttk.Frame(left_frame)
        frame_st_lcd.grid(row=row_left, column=1, pady=2, sticky="w")
        self.st_lcd_entry = tk.Entry(frame_st_lcd, width=8)
        self.st_lcd_entry.pack(side="left")
        self.st_lcd_entry.insert(0, "50")
        self.create_tooltip(self.st_lcd_entry, "Thời gian bật relay LCD (1-90, đơn vị 0.1s)")
        self.st_lcd_entry.bind('<FocusOut>', lambda e: self.validate_linked_entry("ST LCD", self.st_lcd_entry, "50", 1, 90))
        row_left += 1

        # 3. Mode settings
        self.add_entry_field(right_frame, row_right, "Mode DF :", 1, is_combo=True, values=["OFF", "ON"], default="ON", align="w",
                            tooltip="ON/OFF: Bật/tắt điều khiển kéo theo Relay 3")
        row_right += 1
        self.add_entry_field(right_frame, row_right, "Mode END :", 1, is_combo=True, values=["OFF", "ON"], default="ON", align="w",
                            tooltip="ON/OFF: Bật tắt Relay 3 tại end OP chạy lạnh")
        row_right += 1
        self.add_entry_field(right_frame, row_right, "Mode chạy lạnh :", 1, is_combo=True, values=["CL", "OP"], default="OP", align="w",
                            tooltip="CL: Mặc định CL, OP: Mặc định OP")
        row_right += 1
        self.add_entry_field(right_frame, row_right, "Mode xả đá :", 1, is_combo=True, values=["CL", "OP"], default="OP", align="w",
                            tooltip="CL: Xả đá theo CL, OP: Xả đá theo OP")
        row_right += 1
        self.add_entry_field(right_frame, row_right, "Mode SL :", 1, is_combo=True, values=["LCD", "LED"], default="LCD", align="w",
                            tooltip="LCD: Hiển thị LCD, LED: Hiển thị LED")
        row_right += 1

        # 4. lock_time through tried_time
        self.add_entry_field(left_frame, row_left, "Auto lock (Lock time) :", 1, is_uint16=True, default="0", align="w", min_val=0, max_val=999,
                            tooltip="Thời gian tự động khóa (0-999)")
        row_left += 1
        self.add_entry_field(left_frame, row_left, "Độ sáng led xanh :", 1, is_uint8=True, default="0", align="w", min_val=0, max_val=7,
                            tooltip="Điều chỉnh độ sáng LED xanh (0-7)")
        row_left += 1
        self.add_entry_field(left_frame, row_left, "Độ sáng led đỏ :", 1, is_uint8=True, default="0", align="w", min_val=0, max_val=7,
                            tooltip="Điều chỉnh độ sáng LED đỏ (0-7)")
        row_left += 1
        self.add_entry_field(right_frame, row_right, "Mode HCF :", 1, is_combo=True, values=["CF", "H"], default="CF", align="w",
                            tooltip="CF: Mode Cold Fast, H: Mode Hot")
        row_right += 1
        
        self.add_entry_field(right_frame, row_right, "Mode HDF :", 1, is_combo=True, values=["OFF", "ON"], default="OFF", align="w",
                            tooltip="ON/OFF: Bật/tắt mode HDF")
        row_right += 1
        
        self.add_entry_field(right_frame, row_right, "On time Mode LED :", 1, is_combo=True, 
                           values=["R1:1 R2:1", "R1:2 R2:1", "R1:1 R2:2", "R1:2 R2:2"], 
                           default="R1:1 R2:1", align="w",
                           tooltip="Chọn số lần nhấp nháy LED")
        row_right += 1
        self.add_entry_field(right_frame, row_right, "Touch Num :", 1, is_combo=True, values=["1", "2"], default="1", align="w",
                            tooltip="1: Chạm 1 lần, 2: Chạm 2 lần")
        row_right += 1

        pin_frame = ttk.LabelFrame(right_frame, text="PIN Setup")
        pin_frame.grid(row=row_right, column=0, columnspan=2, sticky="ew", padx=5, pady=(14, 5))

        tk.Label(pin_frame, text="PIN password:").grid(row=0, column=0, padx=2, pady=2, sticky="e")
        self.pin_password_entry = tk.Entry(pin_frame, width=8)
        self.pin_password_entry.insert(0, "123")
        self.pin_password_entry.grid(row=0, column=1, padx=2, pady=2, sticky="w")
        self.pin_password_entry.bind('<FocusOut>', lambda e: self.validate_entry_range(self.pin_password_entry, 0, 999, "123"))

        tk.Label(pin_frame, text="Wrong tries:").grid(row=1, column=0, padx=2, pady=2, sticky="e")
        self.pin_wrong_limit_entry = tk.Entry(pin_frame, width=8)
        self.pin_wrong_limit_entry.insert(0, "3")
        self.pin_wrong_limit_entry.grid(row=1, column=1, padx=2, pady=2, sticky="w")
        self.pin_wrong_limit_entry.bind('<FocusOut>', lambda e: self.validate_entry_range(self.pin_wrong_limit_entry, 1, 99, "3"))

        tk.Label(pin_frame, text="Loop:").grid(row=2, column=0, padx=2, pady=2, sticky="e")
        frame_pin_period = ttk.Frame(pin_frame)
        frame_pin_period.grid(row=2, column=1, padx=2, pady=2, sticky="w")
        self.pin_period_day_entry = tk.Entry(frame_pin_period, width=8)
        self.pin_period_day_entry.insert(0, "0")
        self.pin_period_day_entry.pack(side="left")
        self.pin_period_day_entry.bind('<FocusOut>', lambda e: self.validate_entry_range(self.pin_period_day_entry, 0, 999, "0"))
        self.pin_period_unit_var = tk.BooleanVar()
        chk_pin_period_unit = tk.Checkbutton(frame_pin_period, variable=self.pin_period_unit_var)
        chk_pin_period_unit.pack(side="left", padx=(6, 0))
        lbl_pin_period_unit = tk.Label(frame_pin_period, text="hours")
        lbl_pin_period_unit.pack(side="left", padx=(2, 0))
        self.pin_period_unit_var.trace(
            'w',
            lambda *args, l=lbl_pin_period_unit, v=self.pin_period_unit_var:
            l.config(text="days" if v.get() else "hours")
        )
        tk.Label(pin_frame, text="Loop edit:").grid(row=3, column=0, padx=2, pady=2, sticky="e")
        frame_pin_period_show = ttk.Frame(pin_frame)
        frame_pin_period_show.grid(row=3, column=1, padx=2, pady=2, sticky="w")
        self.pin_period_show_var = tk.BooleanVar(value=True)
        chk_pin_period_show = tk.Checkbutton(frame_pin_period_show, variable=self.pin_period_show_var)
        chk_pin_period_show.pack(side="left")
        lbl_pin_period_show = tk.Label(frame_pin_period_show, text="show")
        lbl_pin_period_show.pack(side="left", padx=(2, 0))
        self.pin_period_show_var.trace(
            'w',
            lambda *args, l=lbl_pin_period_show, v=self.pin_period_show_var:
            l.config(text="show" if v.get() else "hide")
        )

        tk.Label(pin_frame, text="Backup password:").grid(row=4, column=0, padx=2, pady=2, sticky="e")
        self.pin_backup_password_entry = tk.Entry(pin_frame, width=8)
        self.pin_backup_password_entry.insert(0, "999")
        self.pin_backup_password_entry.grid(row=4, column=1, padx=2, pady=2, sticky="w")
        self.pin_backup_password_entry.bind('<FocusOut>', lambda e: self.validate_entry_range(self.pin_backup_password_entry, 0, 999, "999"))

        tk.Label(pin_frame, text="Backup time:").grid(row=5, column=0, padx=2, pady=2, sticky="e")
        frame_pin_backup_period = ttk.Frame(pin_frame)
        frame_pin_backup_period.grid(row=5, column=1, padx=2, pady=2, sticky="w")
        self.pin_backup_period_day_entry = tk.Entry(frame_pin_backup_period, width=8)
        self.pin_backup_period_day_entry.insert(0, "1")
        self.pin_backup_period_day_entry.pack(side="left")
        self.pin_backup_period_day_entry.bind('<FocusOut>', lambda e: self.validate_entry_range(self.pin_backup_period_day_entry, 1, 999, "1"))
        self.pin_backup_period_unit_var = tk.BooleanVar()
        chk_pin_backup_period_unit = tk.Checkbutton(frame_pin_backup_period, variable=self.pin_backup_period_unit_var)
        chk_pin_backup_period_unit.pack(side="left", padx=(6, 0))
        lbl_pin_backup_period_unit = tk.Label(frame_pin_backup_period, text="hours")
        lbl_pin_backup_period_unit.pack(side="left", padx=(2, 0))
        self.pin_backup_period_unit_var.trace(
            'w',
            lambda *args, l=lbl_pin_backup_period_unit, v=self.pin_backup_period_unit_var:
            l.config(text="days" if v.get() else "hours")
        )
        row_right += 1

        try:
            contact_frame = ttk.Frame(right_frame)
            contact_frame.grid(row=row_right, column=0, columnspan=2, pady=(2, 8), sticky="n")
            
            contact_label = tk.Label(contact_frame, text="Liên hệ hỗ trợ:", font=("Arial", 9, "bold"))
            contact_label.pack(side="top", pady=(0, 2))

            if getattr(sys, 'frozen', False):
                bundle_dir = sys._MEIPASS
            else:
                bundle_dir = os.path.dirname(os.path.abspath(__file__))
                
            qr_path = os.path.join(bundle_dir, "qr_a_trung.jpg")
            
            if not os.path.exists(qr_path):
                raise FileNotFoundError("Không tìm thấy file qr_a_trung.jpg")
                
            qr_image = Image.open(qr_path)
            qr_image = ImageOps.contain(qr_image, (120, 120), Image.Resampling.LANCZOS)
            qr_photo = ImageTk.PhotoImage(qr_image)
            
            qr_label = tk.Label(contact_frame, image=qr_photo)
            qr_label.image = qr_photo
            qr_label.pack(side="top")
            
        except Exception as e:
            print(f"Lỗi khi tải QR code: {e}")
        row_right += 1

        if not self.use_pass_option:
            pin_frame.grid_remove()

        self.add_entry_field(left_frame, row_left, "Try time :", 1, is_uint16=True, default="0", align="w", min_val=0, max_val=999, has_unit_toggle=True,
                            tooltip="Thời gian thử (0-999 giờ/ngày)")
        row_left += 1

        # Add HCF OP time entry
        self.add_entry_field(left_frame, row_left, "HCF OP time :", 1, is_uint16=True, default="10", align="w", min_val=1, max_val=999,
                            tooltip="Thời gian chạy HCF OP (1-999)")
        row_left += 1

        # Add delay DF on field under HCF OP time
        tk.Label(left_frame, text="delay DF on :").grid(row=row_left, column=0, padx=(1,1), pady=2, sticky="w")
        frame_delay_df = ttk.Frame(left_frame)
        frame_delay_df.grid(row=row_left, column=1, pady=2, sticky="w")
        self.delay_df_on_entry = tk.Entry(frame_delay_df, width=8)
        self.delay_df_on_entry.pack(side="left")
        self.delay_df_on_entry.insert(0, "0")
        self.create_tooltip(self.delay_df_on_entry, "Thời gian Delay DF on (0-90)")
        self.delay_df_on_entry.bind('<FocusOut>', lambda e: self.validate_entry_range(self.delay_df_on_entry, 0, 90, "0"))
        row_left += 1

        # Note: check_box[7] is handled by the checkboxes
        # Enum order: ICE_FLUSH_OP_TIME(0), HIDE_MODE_DF(1), HIDE_MODE_END(2),
        #             HIDE_MODE_SL(3), HIDE_MODE_HCF(4), HIDE_MODE_HDF(5), TRY_TIME_UNIT(6)
        # Note: shutdown and tried_time are handled as extra fields

        # Add min-max time range frame
        time_range_frame = ttk.LabelFrame(left_frame, text="Time Ranges (Min/Max)")
        time_range_frame.grid(row=row_left, column=0, columnspan=2, sticky="ew", padx=5, pady=5)

        self.time_range_entries = []  # List of min/max widgets with per-row constraints

        range_configs = [
            ("Chạy lạnh CL", 1, 999, "1", "999"),
            ("Chạy lạnh OP", 1, 999, "1", "999"),
            ("Xả đá CL", 1, 999, "1", "999"),
            ("Xả đá OP", 1, 999, "1", "999"),
            ("ST LCD", 0, 90, "0", "90"),
            ("ST LED", 0, 90, "0", "90"),
        ]
        for i, (label, min_limit, max_limit, min_default, max_default) in enumerate(range_configs):
            tk.Label(time_range_frame, text=f"{label} Min:").grid(row=i, column=0, padx=2, pady=2, sticky="e")
            min_entry = tk.Entry(time_range_frame, width=5)
            min_entry.insert(0, min_default)
            min_entry.grid(row=i, column=1, padx=2, pady=2)
            min_entry.bind(
                '<FocusOut>',
                lambda e, ent=min_entry, key=label, min_v=min_limit, max_v=max_limit, def_v=min_default:
                self.validate_range_entry(ent, key, True, min_v, max_v, def_v)
            )
            tk.Label(time_range_frame, text=f"{label} Max:").grid(row=i, column=2, padx=2, pady=2, sticky="e")
            max_entry = tk.Entry(time_range_frame, width=5)
            max_entry.insert(0, max_default)
            max_entry.grid(row=i, column=3, padx=2, pady=2)
            max_entry.bind(
                '<FocusOut>',
                lambda e, ent=max_entry, key=label, min_v=min_limit, max_v=max_limit, def_v=max_default:
                self.validate_range_entry(ent, key, False, min_v, max_v, def_v)
            )
            self.time_range_entries.append((label, min_entry, max_entry, min_limit, max_limit, min_default, max_default))

        self.linked_range_fields = {
            "Chạy lạnh CL": (self.run_cl_entry, "5", 1, 999),
            "Chạy lạnh OP": (self.run_op_entry, "5", 1, 999),
            "Xả đá CL": (self.defrost_cl_entry, "60", 1, 999),
            "Xả đá OP": (self.defrost_op_entry, "6", 1, 999),
            "ST LED": (self.st_led_entry, "50", 1, 90),
            "ST LCD": (self.st_lcd_entry, "50", 1, 90),
        }
        for key, (entry, default, fallback_min, fallback_max) in self.linked_range_fields.items():
            entry.bind(
                '<FocusOut>',
                lambda e, range_key=key, ent=entry, def_v=default, min_v=fallback_min, max_v=fallback_max:
                self.validate_linked_entry(range_key, ent, def_v, min_v, max_v)
            )

        row_left += 1
        self.root.deiconify()
        self.root.lift()

    def ask_pass_option(self):
        selected = tk.StringVar(value="")
        self.root.title("PIN Option")
        self.root.geometry("300x105")
        self.root.resizable(False, False)

        option_frame = ttk.Frame(self.root)
        option_frame.pack(fill="both", expand=True, padx=14, pady=14)

        tk.Label(option_frame, text="Chọn option khi tạo dữ liệu:", font=("Arial", 10, "bold")).pack(pady=(0, 10))

        btn_frame = ttk.Frame(option_frame)
        btn_frame.pack()

        def choose(use_pass):
            selected.set("pass" if use_pass else "no_pass")

        ttk.Button(btn_frame, text="Dùng pass", command=lambda: choose(True), width=12).pack(side="left", padx=5)
        ttk.Button(btn_frame, text="Không pass", command=lambda: choose(False), width=12).pack(side="left", padx=5)
        self.root.protocol("WM_DELETE_WINDOW", lambda: choose(True))

        self.root.update_idletasks()
        x = (self.root.winfo_screenwidth() - self.root.winfo_width()) // 2
        y = (self.root.winfo_screenheight() - self.root.winfo_height()) // 2
        self.root.geometry(f"+{x}+{y}")
        self.root.lift()
        self.root.focus_force()
        self.root.attributes("-topmost", True)
        self.root.after(300, lambda: self.root.attributes("-topmost", False))

        self.root.wait_variable(selected)
        use_pass = selected.get() == "pass"
        option_frame.destroy()
        self.root.protocol("WM_DELETE_WINDOW", self.root.destroy)
        return use_pass

    def add_entry_field(self, parent, row, label, display_width=1, column=0, is_uint16=False, 
                       is_uint32=False, is_uint8=False, is_combo=False, values=None, default="0", 
                       align="e", min_val=None, max_val=None, has_unit_toggle=False, tooltip=None):
        lbl = tk.Label(parent, text=label)
        lbl.grid(row=row, column=column, padx=(1,1), pady=2, sticky=align)  # Thêm pady=2
        
        # Add tooltip if provided
        if tooltip:
            self.create_tooltip(lbl, tooltip)

        frame = ttk.Frame(parent)
        frame.grid(row=row, column=column+1, pady=2, sticky="w")  # Thêm pady=2
        
        if is_combo:
            entry = ttk.Combobox(frame, width=6, values=values, state="readonly")  # Increased from 5
            entry.pack(side="left")
            entry.set(default)
            self.eeprom_entries.append(entry)
            
            # Add checkbox with consistent spacing
            if "Mode" in label and "On time" not in label and "chạy lạnh" not in label and "xả đá" not in label:
                var = tk.BooleanVar()
                chk = tk.Checkbutton(frame, variable=var)
                chk.pack(side="left", padx=(10,0))
                lbl_status = tk.Label(frame, text="hide")
                lbl_status.pack(side="left", padx=(2,0))
                var.trace('w', lambda *args, l=lbl_status: 
                         l.config(text="show" if var.get() else "hide"))
                self.checkbox_vars.append(var)  # Thêm vào list để map sau
            
        else:
            entry = tk.Entry(frame, width=8)
            entry.pack(side="left")
            entry.delete(0, tk.END)
            entry.insert(0, default)
            
            # Add unit label for xả đá OP with matching spacing
            if "Xả đá OP" in label:
                var = tk.BooleanVar()
                chk = tk.Checkbutton(frame, variable=var)
                chk.pack(side="left", padx=(10,0))
                lbl_unit = tk.Label(frame, text="minute")
                lbl_unit.pack(side="left", padx=(2,0))
                var.trace('w', lambda *args, l=lbl_unit: 
                         l.config(text="second" if var.get() else "minute"))
                self.checkbox_vars.append(var)  # Thêm vào list để map
            
            # Add checkbox for ST
            if label == "ST LED(100ms) :":
                self.st_extra_check_var = tk.BooleanVar()
                chk = tk.Checkbutton(frame, variable=self.st_extra_check_var)
                chk.pack(side="left", padx=(10,0))
                lbl_status = tk.Label(frame, text="hide")
                lbl_status.pack(side="left", padx=(2,0))
                self.st_extra_check_var.trace('w', lambda *args, l=lbl_status, v=self.st_extra_check_var: 
                         l.config(text="show" if v.get() else "hide"))
            
            # Add unit toggle for try time
            if has_unit_toggle:
                var = tk.BooleanVar() 
                chk = tk.Checkbutton(frame, variable=var)
                chk.pack(side="left", padx=(10,0))
                lbl_unit = tk.Label(frame, text="hours")
                lbl_unit.pack(side="left", padx=(2,0))
                var.trace('w', lambda *args, l=lbl_unit: 
                         l.config(text="days" if var.get() else "hours"))
                self.checkbox_vars.append(var)  # Thêm vào list để map
            
            if min_val is not None and max_val is not None:
                entry.bind('<FocusOut>', lambda e, ent=entry, min_v=min_val, max_v=max_val, def_v=default: 
                         self.validate_entry_range(ent, min_v, max_v, def_v))
            
            if is_uint16 or is_uint32 or is_uint8:
                self.multi_byte_entries[len(self.eeprom_entries)] = {
                    'entry': entry,
                    'bytes': 4 if is_uint32 else (2 if is_uint16 else 1),
                    'default': default  # Store default value
                }
                # Add placeholder entries for the actual bytes
                for _ in range(4 if is_uint32 else (2 if is_uint16 else 1)):
                    self.eeprom_entries.append(None)
            else:
                self.eeprom_entries.append(entry)

        return entry

    def create_tooltip(self, widget, text):
        def enter(event):
            tooltip = tk.Toplevel()
            tooltip.wm_overrideredirect(True)
            tooltip.wm_geometry(f"+{event.x_root+10}+{event.y_root+10}")
            
            label = tk.Label(tooltip, text=text, background="#ffffe0", relief="solid", borderwidth=1)
            label.pack()
            
            self.tooltips[widget] = tooltip
            
        def leave(event):
            if widget in self.tooltips:
                self.tooltips[widget].destroy()
                del self.tooltips[widget]
                
        widget.bind("<Enter>", enter)
        widget.bind("<Leave>", leave)

    def validate_entry_range(self, entry, min_val, max_val, default):
        """Validate and correct entry value to be within min-max range"""
        try:
            value = int(entry.get())
            if value < min_val:
                value = min_val
            elif value > max_val:
                value = max_val
            entry.delete(0, tk.END)
            entry.insert(0, str(value))
        except ValueError:
            entry.delete(0, tk.END)
            entry.insert(0, default)

    def get_linked_range(self, key, fallback_min, fallback_max):
        for label, min_entry, max_entry, min_limit, max_limit, min_default, max_default in self.time_range_entries:
            if label == key:
                min_val = self.read_range_value(min_entry, min_limit, max_limit, min_default)
                max_val = self.read_range_value(max_entry, min_limit, max_limit, max_default)
                if min_val > max_val:
                    max_val = min_val
                    max_entry.delete(0, tk.END)
                    max_entry.insert(0, str(max_val))
                return min_val, max_val
        return fallback_min, fallback_max

    def read_range_value(self, entry, min_limit, max_limit, default):
        try:
            value = int(entry.get())
        except ValueError:
            value = int(default)
        value = max(min_limit, min(value, max_limit))
        entry.delete(0, tk.END)
        entry.insert(0, str(value))
        return value

    def validate_linked_entry(self, key, entry, default, fallback_min, fallback_max):
        min_val, max_val = self.get_linked_range(key, fallback_min, fallback_max)
        self.validate_entry_range(entry, min_val, max_val, default)

    def validate_range_entry(self, entry, key, is_min_entry, min_limit, max_limit, default):
        self.validate_entry_range(entry, min_limit, max_limit, default)
        linked = self.linked_range_fields.get(key)
        if not linked:
            return
        linked_entry, linked_default, fallback_min, fallback_max = linked
        min_val, max_val = self.get_linked_range(key, fallback_min, fallback_max)
        if min_val > max_val:
            if is_min_entry:
                min_val = max_val
                entry.delete(0, tk.END)
                entry.insert(0, str(min_val))
            else:
                max_val = min_val
                entry.delete(0, tk.END)
                entry.insert(0, str(max_val))
        self.validate_entry_range(linked_entry, min_val, max_val, linked_default)

    def get_entry_bytes(self, value, num_bytes):
        """Convert decimal input to specified number of bytes in big endian"""
        try:
            value = int(value)
            # Check value ranges based on number of bytes
            if num_bytes == 1 and value > 255:  # uint8_t
                return [0] * num_bytes
            elif num_bytes == 2 and value > 65535:  # uint16_t 
                return [0] * num_bytes
            elif num_bytes == 4 and value > 4294967295:  # uint32_t
                return [0] * num_bytes
                
            bytes_list = []
            for i in range(num_bytes):
                bytes_list.append(value & 0xFF)
                value >>= 8
            return bytes_list[::-1]  # Reverse list for big endian
        except ValueError:
            return [0] * num_bytes

    def read_int_entry(self, entry, default, min_val, max_val):
        try:
            value = int(entry.get().strip())
        except ValueError:
            value = default
        value = max(min_val, min(value, max_val))
        entry.delete(0, tk.END)
        entry.insert(0, str(value))
        return value

    def append_pin_fields(self, new_data):
        if not self.use_pass_option:
            new_data.extend([0] * 20)
            return

        pin_password = self.read_int_entry(self.pin_password_entry, 123, 0, 999)
        pin_wrong_limit = self.read_int_entry(self.pin_wrong_limit_entry, 3, 1, 99)
        pin_period_day = self.read_int_entry(self.pin_period_day_entry, 0, 0, 999)
        pin_backup_password = self.read_int_entry(self.pin_backup_password_entry, 999, 0, 999)
        pin_backup_period_day = self.read_int_entry(self.pin_backup_period_day_entry, 1, 1, 999)
        pin_period_unit = 1 if self.pin_period_unit_var.get() else 0
        pin_backup_period_unit = 1 if self.pin_backup_period_unit_var.get() else 0
        pin_period_show = 1 if self.pin_period_show_var.get() else 0

        new_data.extend(self.get_entry_bytes(pin_password, 2))
        new_data.extend(self.get_entry_bytes(pin_period_day, 2))
        new_data.extend(self.get_entry_bytes(0, 2))    # pin_day_count
        new_data.append(0)                             # pin_request
        new_data.extend(self.get_entry_bytes(pin_backup_password, 2))
        new_data.append(0)                             # pin_locked
        new_data.append(0)                             # pin_backup_used
        new_data.extend(self.get_entry_bytes(pin_backup_period_day, 2))
        new_data.append(pin_period_unit)
        new_data.append(pin_backup_period_unit)
        new_data.append(1)                             # pin_enable
        new_data.append(pin_period_show)
        new_data.append(pin_wrong_limit)
        new_data.append(0)                             # pin_request_mark
        new_data.append(0)                             # pin_request_mark_inv

    def combo_to_byte(self, value):
        if value == "CF":
            return 1
        if value == "H":
            return 0
        if value in ["OP", "LED", "ON"]:
            return 1
        if value == "R1:2 R2:1":
            return 1
        if value == "R1:1 R2:2":
            return 2
        if value == "R1:2 R2:2":
            return 3
        if value == "2":
            return 1
        return 0

    def build_eeprom_data(self):
        new_data = [2]  # eData.init
        current_idx = 0

        while current_idx < len(self.eeprom_entries):
            entry = self.eeprom_entries[current_idx]
            if isinstance(entry, ttk.Combobox):
                new_data.append(self.combo_to_byte(entry.get()))
                current_idx += 1
            elif current_idx in self.multi_byte_entries:
                entry_info = self.multi_byte_entries[current_idx]
                value = entry_info['entry'].get().strip()
                bytes_list = self.get_entry_bytes(value, entry_info['bytes'])
                new_data.extend(bytes_list)
                current_idx += entry_info['bytes']
            else:
                if entry:
                    value = entry.get().strip()
                    new_data.append(int(value, 16) if value else 0)
                current_idx += 1

        # eData.time_delay
        try:
            df_str = self.delay_df_on_entry.get().strip()
            delay_df_val = int(df_str) if df_str else 0
            if delay_df_val < 0 or delay_df_val > 65535:
                delay_df_val = 0
        except ValueError:
            delay_df_val = 0
        new_data.extend(self.get_entry_bytes(delay_df_val, 2))

        # eData.check_box[7]
        for var in self.checkbox_vars:
            new_data.append(1 if var.get() else 0)

        # eData.shutdown + eData.tried_time + eData.sig_index + eData.sig_count[30]
        new_data.extend([0] * 126)

        # eData.time_min[3][2] and eData.time_max[3][2]
        min_values, max_values = self.get_time_range_values()
        for value in min_values:
            new_data.extend(self.get_entry_bytes(value, 2))
        for value in max_values:
            new_data.extend(self.get_entry_bytes(value, 2))

        # eData.hide_st
        st_check_val = 1 if hasattr(self, 'st_extra_check_var') and self.st_extra_check_var.get() else 0
        new_data.append(st_check_val)

        # eData.ST_LCD
        try:
            lcd_str = self.st_lcd_entry.get().strip()
            st_lcd_val = int(lcd_str) if lcd_str else 50
            if st_lcd_val < 0 or st_lcd_val > 255:
                st_lcd_val = 50
        except ValueError:
            st_lcd_val = 50
        new_data.append(st_lcd_val)

        # PIN fields at the end of eData
        self.append_pin_fields(new_data)

        if len(new_data) != self.EEPROM_DATA_SIZE:
            raise ValueError(f"EEPROM data length mismatch: {len(new_data)} != {self.EEPROM_DATA_SIZE}")
        return new_data

    def detect_tool_path(self):
        try:
            # Get path when running as exe
            if getattr(sys, 'frozen', False):
                bundle_dir = sys._MEIPASS
            else:
                bundle_dir = os.path.dirname(os.path.abspath(__file__))
                
            tool_path = os.path.join(bundle_dir, "NuLink_8051OT.exe")
            
            if os.path.exists(tool_path):
                # Extract tool to temp folder
                temp_dir = tempfile.gettempdir()
                temp_tool = os.path.join(temp_dir, "NuLink_8051OT.exe") 
                shutil.copy2(tool_path, temp_tool)
                return temp_tool
                
            # Fallback to default install path
            default_path = r"C:\Program Files (x86)\Nuvoton Tools\NuLink Command Tool\NuLink_8051OT.exe"
            if os.path.exists(default_path):
                return default_path
                
            messagebox.showerror("Error", "NuLink tool không tìm thấy. Vui lòng chọn file NuLink_8051OT.exe")
            return filedialog.askopenfilename(filetypes=[("Executable files", "*.exe")])
            
        except Exception as e:
            messagebox.showerror("Error", f"Lỗi khi tải NuLink tool: {e}")
            return None

    def browse_file(self):
        file_path = filedialog.askopenfilename(filetypes=[("Hex files", "*.hex")])
        if file_path:
            self.file_path.set(file_path)
            messagebox.showinfo("Success", "Hex file selected")

    def check_connection(self):
        """Check if device is connected and update status"""
        if not self.tool_path:
            messagebox.showerror("Error", "NuLink tool not found")
            return False
            
        command = [self.tool_path, "-p"]
        info = self.run_command(command)
        if info:
            # Extract MCU info after second >>>
            info_parts = info.split('>>>')
            if len(info_parts) > 2:
                mcu_info = ''.join(c for c in info_parts[2] if c.isalnum())[:9]
                self.connect_status.config(text="Status: Connected", fg="green")
                self.mcu_info.config(text=f"MCU: {mcu_info}")
                self.enable_buttons()
            else:
                self.connect_status.config(text="Status: Connected", fg="green")
                self.mcu_info.config(text="")
                self.enable_buttons()
            return True
        else:
            self.connect_status.config(text="Status: Disconnected", fg="red")
            self.mcu_info.config(text="")
            self.disable_buttons()
            messagebox.showerror("Error", "Device not connected")
            return False

    def save_and_flash(self):
        if not self.check_connection():
            return
            
        hex_file_path = self.file_path.get()
        if not hex_file_path:
            messagebox.showerror("Error", "Please select a HEX file first.")
            return

        try:
            new_data = self.build_eeprom_data()

            # Continue with hex file handling...
            hex_file = IntelHex(hex_file_path)
            
            # Write all data including init byte
            for i, value in enumerate(new_data):
                addr = self.EEPROM_START_ADDRESS - 1 + i  # Start from 0x7F00
                hex_file[addr] = value

            # Create temporary file with merged data
            temp_file = hex_file_path.replace('.hex', '_merged.hex')
            hex_file.write_hex_file(temp_file)

            # Flash the merged file
            if self.tool_path:
                self.run_command([self.tool_path, "-e", "ALL"])
                self.run_command([self.tool_path, "-reset"])
                command = [self.tool_path, "-w", "APROM", temp_file]
                result = self.run_command(command)
                if result:
                    # Only lock if checkbox checked
                    if self.lock_chip_var.get():
                        lock_cmd = [self.tool_path, "-w", "cfg0", "0xFFFFFFFD"] 
                        self.run_command(lock_cmd)
                    messagebox.showinfo("Success", "Save & Flash!")
                
            # Clean up
            if os.path.exists(temp_file):
                os.remove(temp_file)

        except ValueError as e:
            messagebox.showerror("Error", "Invalid input value")
            return
        except Exception as e:
            messagebox.showerror("Error", f"Failed to merge and flash:\n{e}")

    def erase_microcontroller(self):
        if not self.check_connection():
            return
            
        if self.tool_path:
            command = [self.tool_path, "-e", "ALL"]
            if self.run_command(command):
                messagebox.showinfo("Success", "Erase!")

    def reset_microcontroller(self):
        if not self.check_connection():
            return
            
        if self.tool_path:
            command = [self.tool_path, "-reset"]
            if self.run_command(command):
                messagebox.showinfo("Success", "Reset!")

    def flash_microcontroller(self):
        if not self.check_connection():
            return
            
        if self.tool_path:
            # Erase và reset không hiện thông báo
            self.run_command([self.tool_path, "-e", "ALL"])
            self.run_command([self.tool_path, "-reset"])
            
            hex_file = self.file_path.get()
            if hex_file:
                # Flash
                flash_cmd = [self.tool_path, "-w", "APROM", hex_file]
                if self.run_command(flash_cmd):
                    # Only lock if checkbox checked
                    if self.lock_chip_var.get():
                        lock_cmd = [self.tool_path, "-w", "cfg0", "0xFFFFFFFD"]
                        self.run_command(lock_cmd)
                    messagebox.showinfo("Success", "Flash!")

    def connect_device(self):
        try:
            if not messagebox.askyesno("Xác nhận", "Bộ nhớ sẽ bị xóa trước khi connect. Bạn có muốn tiếp tục?"):
                return

            if self.tool_path:
                command = [self.tool_path, "-e", "ALL"]
                self.run_command(command)

            command = [self.tool_path, "-p"]
            info = self.run_command(command)
            if info:
                # Extract MCU info after second >>>
                info_parts = info.split('>>>')
                if len(info_parts) > 2:
                    mcu_info = ''.join(c for c in info_parts[2] if c.isalnum())[:9]
                    self.connect_status.config(text="Status: Connected", fg="green")
                    self.mcu_info.config(text=f"MCU: {mcu_info}")
                    self.enable_buttons()
                else:
                    self.connect_status.config(text="Status: Connected", fg="green")
                    self.mcu_info.config(text="")
                    self.enable_buttons()
            else:
                self.connect_status.config(text="Status: Disconnected", fg="red")
                self.mcu_info.config(text="")
                self.disable_buttons()
        except Exception as e:
            self.connect_status.config(text="Status: Disconnected", fg="red")
            self.mcu_info.config(text="")
            self.disable_buttons()

    def enable_buttons(self):
        """Enable all control buttons"""
        self.flash_btn["state"] = "normal"
        self.erase_btn["state"] = "normal"
        self.reset_btn["state"] = "normal" 
        self.save_flash_btn["state"] = "normal"

    def disable_buttons(self):
        """Disable all control buttons"""
        self.flash_btn["state"] = "disabled"
        self.erase_btn["state"] = "disabled"
        self.reset_btn["state"] = "disabled"
        self.save_flash_btn["state"] = "disabled"

    def get_info(self):
        pass

    def run_command(self, command):
        try:
            startupinfo = subprocess.STARTUPINFO()
            startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
            startupinfo.wShowWindow = subprocess.SW_HIDE
            
            result = subprocess.run(command, 
                                  check=True,
                                  capture_output=True,
                                  text=True,
                                  startupinfo=startupinfo)
            return result.stdout
        except subprocess.CalledProcessError as e:
            messagebox.showerror("Error", e.stderr)
            return ""

    def generate_hex(self):
        """Generate merged hex file without flashing"""
        # No connection check needed for generate_hex since it doesn't interact with device
        try:
            save_path = filedialog.asksaveasfilename(
                defaultextension=".hex",
                initialfile=self.output_hex_name.get(),
                filetypes=[("Hex files", "*.hex")]
            )
            if not save_path:
                return

            hex_file_path = self.file_path.get()
            if not hex_file_path:
                messagebox.showerror("Error", "Please select input HEX file first.")
                return

            new_data = self.build_eeprom_data()

            hex_file = IntelHex(hex_file_path)
            for i, value in enumerate(new_data):
                addr = self.EEPROM_START_ADDRESS - 1 + i
                hex_file[addr] = value

            hex_file.write_hex_file(save_path)
            messagebox.showinfo("Success", f"Generated hex file: {save_path}")

        except Exception as e:
            messagebox.showerror("Error", f"Failed to generate hex file:\n{e}")

    def get_time_range_values(self):
        """Get min-max values from time range entries"""
        min_values = []
        max_values = []
        for _, min_entry, max_entry, min_limit, max_limit, min_default, max_default in self.time_range_entries:
            try:
                min_val = int(min_entry.get())
                max_val = int(max_entry.get())
                min_val = max(min_limit, min(min_val, max_limit))
                max_val = max(min_limit, min(max_val, max_limit))
                min_values.append(min_val)
                max_values.append(max_val)
            except ValueError:
                min_values.append(int(min_default))
                max_values.append(int(max_default))
        return min_values, max_values

if __name__ == "__main__":
    root = tk.Tk()
    app = FlashToolGUI(root)
    root.mainloop()
