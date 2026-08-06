#ifndef __Global_variables_H
#define __Global_variables_H
#include "header.h"
#include "millis.h"
#include "seg_map.h"

#ifndef u8d
typedef uint8_t data u8d;
#endif

#ifndef u8dv
typedef volatile uint8_t data u8dv;
#endif

#ifndef u32dv
typedef volatile uint32_t data u32dv;
#endif

TM1650_class RED   = {TM1_set_SCL, TM1_set_SDA, TM1_read_SDA};
TM1650_class GREEN = {TM2_set_SCL, TM2_set_SDA, TM2_read_SDA};

volatile TICK_TYPE millis_second;
TICK_TYPE          sys_tick;
TICK_TYPE          tick;
TICK_TYPE          tick_hcf;
TICK_TYPE          tick_open_time;
TICK_TYPE          tick_sig;
TICK_TYPE          tick_delay_30s_RL3;
uint16_t           sig_time=0;
uint16_t           open_time=0;
uint16_t           open_time_is_sucess=0;
uint8_t            enable_open_time=0;
uint8_t            test=1; // thay sig
uint32_t           countdown;    // 100ms unit
uint32_t           countdown_RL3; 
uint32_t           countdown_delay_RL3; 
uint32_t           countdown_sig; 
uint32_t           event_ST_on;  // 100ms unit
uint32_t           event_ST_off; // 100ms unit
uint32_t           event_ST_on2;  // 100ms unit
uint32_t           event_ST_off2; // 100ms unit
uint8_t            ST_OFFSET = 0;
uint8_t 					 is_delay=0;
typedef enum program_t { COOL, ICE_FLUSH, IDLE, SETTING, ADVANCE_SETTING, PASSWORD_CHECK, PASSWORD_CHANGE, PASSWORD_TIME_SETTING, PASSWORD_BACKUP } program_t;
program_t program      = IDLE;
bool               program_init = false;
bool               hcf_mode   = false;
uint8_t            hcf_mode_timeout = 0;
uint8_t            enable_hcf_CL_timeout_sig = 0;
uint8_t            run_hcf_CL_timeout_sig = 0;
uint8_t            enable_hcf_OP_timeout_sig = 0;
uint8_t            mode_LED_on = 0;
uint8_t            RL1_is_on = 0;
uint8_t            RL2_is_on = 0;
uint8_t            RL2_LED_skip_on = 0;
uint8_t            RL2_LED_had_on = 0;
uint8_t            RL3_sig_is_on = 0;
/* Logical output states for UART telemetry; updated by RLx_ON/RLx_OFF. */
uint8_t            RL1_output_is_on = 0;
uint8_t            RL2_output_is_on = 0;
uint8_t            RL3_output_is_on = 0;
uint8_t            mode_END_disable = 0;
uint8_t 					 test;
uint8_t HCF_temp;
enum button_name { SET, UP, DOWN };
enum { MIN, MAX };
enum { ST=2};



uint8_t btn_pin[btnTotal] = {SET_PIN, UP_PIN, DOWN_PIN};

typedef enum switch_t { SW_COOL, SW_ICE_FLUSH, SW_IDLE } switch_t;
switch_t switch_state = SW_IDLE;

typedef enum RL_t { R1, R2, R3 } RL_t;
Progress_t progress;
Progress_t progress_RL3;

uint8_t SET_press_timeout  = 0;
uint8_t UP_press_timeout = 0;
uint8_t DOWN_press_timeout = 0;
uint8_t setting_timeout    = 0;
#define setting_timeout_refresh() setting_timeout = 12;


#define adv_settings_menu_total 6
typedef enum adv_setting_t {
	set_FirstProgress,  // set_FirstProgress OP/CL
	set_DF,            	// set_DF ON/OFF
	set_HCF,            // set_HCF //H,CF
	set_Mode,           // set_Mode SL // Lcd, LEd
	set_ST,            	// set_ST
	set_END,           	// eData.END
};
uint16_t adv_minmax[adv_settings_menu_total][2] = {
    {0, 1},   // set_FirstProgress OP/CL
    {0, 1}, 	// set_DF ON/OFF
    {0, 1},   // eData.END
		{0, 1},   // set_Mode SL // Lcd, LEd
    {0, 90},  // set_ST
		{0, 1}    // set_HCF  //H,CF
};

uint8_t adv_rotate[adv_settings_menu_total] = {
    1, // set_FirstProgress, OP/CL
    1, // set_DF 0-30.0
    1, // eData.END
		1, // set_Mode SL // Lcd, LEd  
		0, // set_ST
		1  // set_HCF //H,CF 
}; // rotate adjust_parameter
uint8_t adv_increase[adv_settings_menu_total] = {
    0, // set_FirstProgress OP/CL
    0, // set_DF 0-30.0
    0, // eData.END
		0, // set_Mode SL // Lcd, LEd
    0, // set_ST
		0 // set_HCF //H,CF 
}; // when hold UP, DOWN to adjust parameter
uint8_t adv_total_menu;

TICK_TYPE setting_tick;
uint8_t   SET_MODE; // COOL or ICE_FLUSH
uint8_t   setting_idx;
uint8_t   setting_led_state = 0;
uint8_t   setting_increase;
uint8_t   adj_const = 1;
uint32_t  setting_para; // setting parameter
uint32_t  password_para;
uint32_t  password_day_tick;
uint8_t   password_gate;
uint8_t   password_change_enable;
uint8_t   password_wrong_count;
uint8_t   password_blink_state;
TICK_TYPE password_blink_tick;
#define password_max_value 999
#define password_hour_tick_max 36000UL
#define password_day_tick_max  864000UL
#define password_request_mark  0x6D

typedef enum lock_t { LOCK_OFF, LOCK_ON } lock_t;
lock_t  lock         = LOCK_OFF;
uint16_t lock_timeout;
uint8_t show_mode=0;
uint8_t show_his=0;
int8_t list_data=0;
uint32_t count_log=0;
uint8_t num_hex[10]={0x3F,0x06,0x5B,0x4F,0x66,0x6D,0x7D,0x07,0x7F,0x6F};
#define lock_timeout_max (eData.lock_time * 10) 
#define lock_timeout_refresh() lock_timeout = lock_timeout_max

uint32_t shutdown_timeout; //shutdown time


#define shutdown_timeout_refresh() do{shutdown_timeout = 0;} while(0)

uint8_t popup_timeout = 0;
#define popup_timeout_max       10 // 100ms unit
#define popup_timeout_refresh() popup_timeout = popup_timeout_max
#define popup_timeout_refresh_4s() popup_timeout = 40

#define adc_count_max 8
uint16_t          vdd = 0;
volatile uint16_t ADC_Result[2][adc_count_max];
const uint8_t     adc_channel_idx[2] = {AIN3, Band_Gap};
uint16_t          adc_avg[2]         = {0, 0};
uint8_t           adc_stable_count   = 0;
u8dv              adc_idx            = 0;
u8dv              adc_done           = 0;
u8dv              adc_channel        = 0;

#ifdef DEBUG
uint32_t c;

#endif

#endif
