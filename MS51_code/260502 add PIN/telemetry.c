#ifndef _MS51_TELEMETRY_C_
#define _MS51_TELEMETRY_C_

#include "telemetry.h"

/*
 * UART1 shares the two existing ICP wires only while the MS51 is running:
 *
 *   P0.2 / ICE_CLK  <- ESP32-S3 GPIO5 (UART1 RXD)
 *   P1.6 / ICE_DAT  -> ESP32-S3 GPIO6 (UART1 TXD)
 *
 * The ESP32 removes its UART peripheral from both GPIOs before it enters ICP,
 * so the programmer and runtime UART never own the wires at the same time.
 */

#define TELEMETRY_INTERVAL_MS       1000UL
#define TELEMETRY_RX_FRAME_TIMEOUT_MS 250UL
#define TELEMETRY_TX_BUFFER_SIZE    256u
#define TELEMETRY_RX_BUFFER_SIZE    256u
#define TELEMETRY_CONFIG_SIZE       217u

/* Binary UART protocol.  It is deliberately length-delimited so the raw
 * app.py EEPROM image can be transferred without escaping its bytes.
 *
 * Request:  A5 command sequence length payload... crc16_hi crc16_lo
 * Response: A6 command sequence status length payload... crc16_hi crc16_lo
 * CRC16-CCITT covers every byte after the SOF through the payload.
 */
#define TELEMETRY_REQUEST_SOF       0xA5u
#define TELEMETRY_RESPONSE_SOF      0xA6u
#define TELEMETRY_COMMAND_GET       0x47u /* 'G' */
#define TELEMETRY_COMMAND_SET       0x53u /* 'S' */

#define TELEMETRY_STATUS_OK          0u
#define TELEMETRY_STATUS_BAD_FRAME   1u
#define TELEMETRY_STATUS_BAD_CONFIG  2u

typedef enum {
	telemetry_rx_wait_sof = 0,
	telemetry_rx_state_command,
	telemetry_rx_state_sequence,
	telemetry_rx_state_length,
	telemetry_rx_state_data,
	telemetry_rx_state_crc_high,
	telemetry_rx_state_crc_low
} telemetry_rx_state_t;

static volatile uint8_t telemetry_tx_busy;
static volatile uint8_t telemetry_tx_index;
static volatile uint8_t telemetry_tx_length;
static uint8_t xdata telemetry_tx_buffer[TELEMETRY_TX_BUFFER_SIZE];
static TICK_TYPE telemetry_tick;
static uint8_t telemetry_buffer_overflow;

/* UART1 RX is kept extremely short in the ISR.  The main loop parses this
 * 256-byte ring, so a whole 223-byte SET frame fits even when the control loop
 * is temporarily busy updating displays or relays. */
static uint8_t xdata telemetry_rx_buffer[TELEMETRY_RX_BUFFER_SIZE];
static volatile uint8_t telemetry_rx_head;
static volatile uint8_t telemetry_rx_tail;
static volatile uint8_t telemetry_rx_overflow;

static telemetry_rx_state_t telemetry_rx_state;
static uint8_t telemetry_rx_command;
static uint8_t telemetry_rx_sequence;
static uint8_t telemetry_rx_length;
static uint8_t telemetry_rx_index;
static uint16_t telemetry_rx_crc;
static uint16_t telemetry_rx_received_crc;
static uint8_t xdata telemetry_rx_payload[TELEMETRY_CONFIG_SIZE];
static uint8_t telemetry_command_pending;
static uint8_t telemetry_pending_command;
static uint8_t telemetry_pending_sequence;
static TICK_TYPE telemetry_rx_tick;

static uint8_t telemetry_response_pending;
static uint8_t telemetry_response_command;
static uint8_t telemetry_response_sequence;
static uint8_t telemetry_response_status;
static uint8_t telemetry_response_with_config;
static uint8_t telemetry_reset_after_response;
static volatile uint8_t telemetry_reset_after_tx;
static volatile uint8_t telemetry_reset_ready;

static uint16_t telemetry_crc16_update(uint16_t crc, uint8_t value)
{
	uint8_t bit_index;
	crc ^= (uint16_t)value << 8;
	for (bit_index = 0; bit_index < 8; ++bit_index)
	{
		if (crc & 0x8000u)
			crc = (uint16_t)((crc << 1) ^ 0x1021u);
		else
			crc <<= 1;
	}
	return crc;
}

static void telemetry_append_char(uint8_t value)
{
	if (telemetry_tx_length < TELEMETRY_TX_BUFFER_SIZE)
	{
		telemetry_tx_buffer[telemetry_tx_length++] = value;
	}
	else
	{
		telemetry_buffer_overflow = 1;
	}
}

static void telemetry_append_text(const char code *text)
{
	if (text == 0)
		return;
	while (*text)
	{
		telemetry_append_char((uint8_t)*text++);
	}
}

static void telemetry_append_u16(uint16_t value)
{
	uint8_t digits[5];
	uint8_t count = 0;
	do
	{
		digits[count++] = (uint8_t)('0' + (value % 10));
		value /= 10;
	} while (value && count < sizeof(digits));

	while (count)
	{
		telemetry_append_char(digits[--count]);
	}
}

static void telemetry_append_var_prefix(const char code *name)
{
	telemetry_append_text("@var,");
	telemetry_append_text(name);
	telemetry_append_char(',');
}

static void telemetry_append_var_text(const char code *name, const char code *value)
{
	telemetry_append_var_prefix(name);
	telemetry_append_text(value);
	telemetry_append_char('\r');
	telemetry_append_char('\n');
}

static void telemetry_append_var_u16(const char code *name, uint16_t value)
{
	telemetry_append_var_prefix(name);
	telemetry_append_u16(value);
	telemetry_append_char('\r');
	telemetry_append_char('\n');
}

static const char code *telemetry_mode_text(void)
{
	switch (program)
	{
	case COOL:
		return "COOL";
	case ICE_FLUSH:
		return "ICE_FLUSH";
	case SETTING:
		return "SETTING";
	case ADVANCE_SETTING:
		return "ADVANCE_SETTING";
	case PASSWORD_CHECK:
	case PASSWORD_CHANGE:
	case PASSWORD_TIME_SETTING:
	case PASSWORD_BACKUP:
		return "PASSWORD_CONFIG";
	case IDLE:
	default:
		return "IDLE";
	}
}

static uint8_t telemetry_segment_to_digit(uint8_t segment, uint8_t *digit)
{
	uint8_t i;
	for (i = 0; i < 10; ++i)
	{
		if (segment == seg_map[i])
		{
			*digit = i;
			return 1;
		}
	}
	return 0;
}

/* Decode the actual three-digit image last written to a TM1650 display. */
static uint8_t telemetry_display_number(TM1650_class *display, uint16_t *value)
{
	uint8_t i;
	uint8_t digit;
	uint8_t saw_digit = 0;
	uint8_t saw_leading_blank = 0;
	uint16_t multiplier = 1;
	uint16_t result = 0;

	for (i = 0; i < display->info.DIG; ++i)
	{
		if (display->dt_write[i] == sMap(OFF))
		{
			if (!saw_digit)
				return 0;
			saw_leading_blank = 1;
		}
		else
		{
			if (saw_leading_blank ||
				!telemetry_segment_to_digit(display->dt_write[i], &digit))
				return 0;
			result += (uint16_t)digit * multiplier;
			saw_digit = 1;
		}
		multiplier *= 10;
	}

	if (!saw_digit)
		return 0;
	*value = result;
	return 1;
}

static uint8_t telemetry_display_contains_time(void)
{
	if (show_his || popup_timeout)
		return 0;
	return program == COOL || program == ICE_FLUSH || program == SETTING;
}

static void telemetry_append_display(const char code *name, TM1650_class *display)
{
	uint16_t value;
	if (telemetry_display_contains_time() && telemetry_display_number(display, &value))
	{
		telemetry_append_var_u16(name, value);
	}
	else
	{
		telemetry_append_var_text(name, "N/A");
	}
}

static void telemetry_build_snapshot(void)
{
	uint8_t relay1_on;
	uint8_t relay2_on;
	uint8_t relay3_on;
	uint8_t saved_ea;

	telemetry_tx_length = 0;
	telemetry_buffer_overflow = 0;
	saved_ea = EA;
	EA = 0;
	/* ON/OFF macros update these logical states, independent of relay polarity. */
	relay1_on = RL1_output_is_on;
	relay2_on = RL2_output_is_on;
	relay3_on = RL3_output_is_on;
	EA = saved_ea;

	telemetry_append_var_text("mode", telemetry_mode_text());
	telemetry_append_display("green_min", &GREEN);
	telemetry_append_display("red_min", &RED);
	telemetry_append_var_text("relay1", relay1_on ? "ON" : "OFF");
	telemetry_append_var_text("relay2", relay2_on ? "ON" : "OFF");
	telemetry_append_var_text("relay3", relay3_on ? "ON" : "OFF");
}

static void telemetry_start_transmit(void)
{
	uint8_t saved_ea;
	uint8_t saved_sfrs;

	if (telemetry_buffer_overflow || telemetry_tx_length == 0 || telemetry_tx_busy)
		return;

	saved_ea = EA;
	EA = 0;
	saved_sfrs = SFRS;
	telemetry_tx_busy = 1;
	telemetry_tx_index = 1;
	SFRS = 0;
	TI_1 = 0;
	SBUF_1 = telemetry_tx_buffer[0];
	/* EIE1.0 stays enabled permanently so RX remains live between snapshots. */
	SFRS = saved_sfrs;
	EA = saved_ea;
}

static void telemetry_reset_rx_parser(void)
{
	telemetry_rx_state = telemetry_rx_wait_sof;
	telemetry_rx_command = 0;
	telemetry_rx_sequence = 0;
	telemetry_rx_length = 0;
	telemetry_rx_index = 0;
	telemetry_rx_crc = 0xFFFFu;
	telemetry_rx_received_crc = 0;
}

static uint16_t telemetry_read_u16_be(const uint8_t xdata *blob)
{
	return (uint16_t)(((uint16_t)blob[0] << 8) | blob[1]);
}

static uint32_t telemetry_read_u32_be(const uint8_t xdata *blob)
{
	return ((uint32_t)blob[0] << 24) | ((uint32_t)blob[1] << 16) |
		   ((uint32_t)blob[2] << 8) | blob[3];
}

static uint8_t telemetry_value_in_range_u16(uint16_t value, uint16_t minimum,
									 uint16_t maximum)
{
	return value >= minimum && value <= maximum;
}

static uint8_t telemetry_config_blob_is_valid(const uint8_t xdata *blob)
{
	uint8_t i;
	uint16_t minimum;
	uint16_t maximum;

	if (blob[0] != eeprom_init_val)
		return 0;

	for (i = 0; i < 4; ++i)
	{
		const uint32_t time_value = telemetry_read_u32_be(blob + 1u + ((uint16_t)i * 4u));
		if (time_value == 0 || time_value > 999UL)
			return 0;
	}
	if (blob[0x11] == 0 || blob[0x12] > 90 || blob[0x13] > 1 ||
		blob[0x14] > 1 || blob[0x15] > 1 || blob[0x16] > 1 ||
		blob[0x17] > 1 || telemetry_read_u16_be(blob + 0x18) > 999 ||
		blob[0x1A] > 7 || blob[0x1B] > 7 || blob[0x1C] > 1 ||
		blob[0x1D] > 1 || blob[0x1E] > 3 || blob[0x1F] > 1 ||
		telemetry_read_u16_be(blob + 0x20) > 999 ||
		!telemetry_value_in_range_u16(telemetry_read_u16_be(blob + 0x22), 1, 999) ||
		telemetry_read_u16_be(blob + 0x24) > 90)
		return 0;

	for (i = 0; i < 7; ++i)
	{
		if (blob[0x26 + i] > 1)
			return 0;
	}

	for (i = 0; i < 6; ++i)
	{
		minimum = telemetry_read_u16_be(blob + 0xAB + ((uint16_t)i * 2u));
		maximum = telemetry_read_u16_be(blob + 0xB7 + ((uint16_t)i * 2u));
		if (i < 4)
		{
			if (!telemetry_value_in_range_u16(minimum, 1, 999) ||
				!telemetry_value_in_range_u16(maximum, 1, 999))
				return 0;
		}
		else if (!telemetry_value_in_range_u16(minimum, 0, 90) ||
				 !telemetry_value_in_range_u16(maximum, 0, 90))
		{
			return 0;
		}
		if (minimum > maximum)
			return 0;
	}

	if (blob[0xC3] > 1 || blob[0xC4] > 90)
		return 0;

	/* app.py's no-PIN option intentionally writes all 20 PIN bytes as zero. */
	if (blob[0xD4] == 0)
	{
		for (i = 0xC5; i <= 0xD8; ++i)
		{
			if (blob[i] != 0)
				return 0;
		}
		return 1;
	}
	if (blob[0xD4] != 1 || telemetry_read_u16_be(blob + 0xC5) > 999 ||
		telemetry_read_u16_be(blob + 0xC7) > 999 ||
		telemetry_read_u16_be(blob + 0xCC) > 999 ||
		!telemetry_value_in_range_u16(telemetry_read_u16_be(blob + 0xD0), 1, 999) ||
		blob[0xD2] > 1 || blob[0xD3] > 1 || blob[0xD5] > 1 ||
		!telemetry_value_in_range_u16(blob[0xD6], 1, 99))
		return 0;

	/* The web intentionally follows app.py and clears transient PIN state. */
	if (telemetry_read_u16_be(blob + 0xC9) != 0 || blob[0xCB] != 0 ||
		blob[0xCE] != 0 || blob[0xCF] != 0 || blob[0xD7] != 0 || blob[0xD8] != 0)
		return 0;
	return 1;
}

static void telemetry_schedule_config_response(uint8_t command, uint8_t sequence,
										 uint8_t status, uint8_t with_config,
										 uint8_t reset_after_response)
{
	telemetry_response_command = command;
	telemetry_response_sequence = sequence;
	telemetry_response_status = status;
	telemetry_response_with_config = with_config;
	telemetry_reset_after_response = reset_after_response;
	telemetry_response_pending = 1;
}

static void telemetry_build_config_response(void)
{
	uint8_t index;
	uint8_t length = telemetry_response_with_config ? TELEMETRY_CONFIG_SIZE : 0;
	uint16_t crc = 0xFFFFu;
	uint8_t xdata *config_data = (uint8_t xdata *)&eData;

	telemetry_tx_length = 0;
	telemetry_buffer_overflow = 0;
	telemetry_append_char(TELEMETRY_RESPONSE_SOF);
	telemetry_append_char(telemetry_response_command);
	crc = telemetry_crc16_update(crc, telemetry_response_command);
	telemetry_append_char(telemetry_response_sequence);
	crc = telemetry_crc16_update(crc, telemetry_response_sequence);
	telemetry_append_char(telemetry_response_status);
	crc = telemetry_crc16_update(crc, telemetry_response_status);
	telemetry_append_char(length);
	crc = telemetry_crc16_update(crc, length);
	for (index = 0; index < length; ++index)
	{
		telemetry_append_char(config_data[index]);
		crc = telemetry_crc16_update(crc, config_data[index]);
	}
	telemetry_append_char((uint8_t)(crc >> 8));
	telemetry_append_char((uint8_t)crc);
}

static void telemetry_finish_config_command(void)
{
	uint8_t index;
	uint8_t xdata *config_data = (uint8_t xdata *)&eData;

	if (telemetry_pending_command == TELEMETRY_COMMAND_GET)
	{
		telemetry_schedule_config_response(telemetry_pending_command,
			telemetry_pending_sequence, TELEMETRY_STATUS_OK, 1, 0);
	}
	else if (telemetry_pending_command == TELEMETRY_COMMAND_SET)
	{
		if (!telemetry_config_blob_is_valid(telemetry_rx_payload))
		{
			telemetry_schedule_config_response(telemetry_pending_command,
				telemetry_pending_sequence, TELEMETRY_STATUS_BAD_CONFIG, 0, 0);
		}
		else
		{
			for (index = 0; index < TELEMETRY_CONFIG_SIZE; ++index)
			{
				config_data[index] = telemetry_rx_payload[index];
			}
			/* One erase/write operation matches app.py's complete EEPROM update. */
			AP_EEPROM_Commit();
			telemetry_schedule_config_response(telemetry_pending_command,
				telemetry_pending_sequence, TELEMETRY_STATUS_OK, 0, 1);
		}
	}
	else
	{
		telemetry_schedule_config_response(telemetry_pending_command,
			telemetry_pending_sequence, TELEMETRY_STATUS_BAD_FRAME, 0, 0);
	}
	telemetry_command_pending = 0;
	telemetry_pending_command = 0;
	telemetry_pending_sequence = 0;
}

static void telemetry_process_command_byte(uint8_t value)
{
	if (telemetry_command_pending)
		return;
	ticks_reset(&telemetry_rx_tick);

	switch (telemetry_rx_state)
	{
	case telemetry_rx_wait_sof:
		if (value == TELEMETRY_REQUEST_SOF)
		{
		telemetry_rx_state = telemetry_rx_state_command;
			telemetry_rx_crc = 0xFFFFu;
		}
		break;
	case telemetry_rx_state_command:
		if (value != TELEMETRY_COMMAND_GET && value != TELEMETRY_COMMAND_SET)
		{
			telemetry_reset_rx_parser();
			break;
		}
		telemetry_rx_command = value;
		telemetry_rx_crc = telemetry_crc16_update(telemetry_rx_crc, value);
		telemetry_rx_state = telemetry_rx_state_sequence;
		break;
	case telemetry_rx_state_sequence:
		telemetry_rx_sequence = value;
		telemetry_rx_crc = telemetry_crc16_update(telemetry_rx_crc, value);
		telemetry_rx_state = telemetry_rx_state_length;
		break;
	case telemetry_rx_state_length:
		telemetry_rx_length = value;
		telemetry_rx_crc = telemetry_crc16_update(telemetry_rx_crc, value);
		if ((telemetry_rx_command == TELEMETRY_COMMAND_GET && value != 0) ||
			(telemetry_rx_command == TELEMETRY_COMMAND_SET &&
			 value != TELEMETRY_CONFIG_SIZE))
		{
			telemetry_schedule_config_response(telemetry_rx_command,
				telemetry_rx_sequence, TELEMETRY_STATUS_BAD_FRAME, 0, 0);
			telemetry_reset_rx_parser();
			break;
		}
		telemetry_rx_index = 0;
		telemetry_rx_state = value ? telemetry_rx_state_data : telemetry_rx_state_crc_high;
		break;
	case telemetry_rx_state_data:
		telemetry_rx_payload[telemetry_rx_index++] = value;
		telemetry_rx_crc = telemetry_crc16_update(telemetry_rx_crc, value);
		if (telemetry_rx_index >= telemetry_rx_length)
			telemetry_rx_state = telemetry_rx_state_crc_high;
		break;
	case telemetry_rx_state_crc_high:
		telemetry_rx_received_crc = (uint16_t)value << 8;
		telemetry_rx_state = telemetry_rx_state_crc_low;
		break;
	case telemetry_rx_state_crc_low:
		telemetry_rx_received_crc |= value;
		if (telemetry_rx_received_crc == telemetry_rx_crc)
		{
			/* Preserve these before resetting the parser.  The main loop commits
			 * after the full frame is parsed, so it must not depend on parser state. */
			telemetry_pending_command = telemetry_rx_command;
			telemetry_pending_sequence = telemetry_rx_sequence;
			telemetry_command_pending = 1;
		}
		else
		{
			telemetry_schedule_config_response(telemetry_rx_command,
				telemetry_rx_sequence, TELEMETRY_STATUS_BAD_FRAME, 0, 0);
		}
		telemetry_reset_rx_parser();
		break;
	default:
		telemetry_reset_rx_parser();
		break;
	}
}

static uint8_t telemetry_rx_pop(uint8_t *value)
{
	uint8_t saved_ea;
	uint8_t has_value = 0;

	saved_ea = EA;
	EA = 0;
	if (telemetry_rx_tail != telemetry_rx_head)
	{
		*value = telemetry_rx_buffer[telemetry_rx_tail++];
		has_value = 1;
	}
	EA = saved_ea;
	return has_value;
}

static void telemetry_process_rx(void)
{
	uint8_t value;
	uint8_t saved_ea;
	uint8_t overflowed;

	saved_ea = EA;
	EA = 0;
	overflowed = telemetry_rx_overflow;
	telemetry_rx_overflow = 0;
	EA = saved_ea;
	if (overflowed)
	{
		telemetry_reset_rx_parser();
	}
	if (telemetry_rx_state != telemetry_rx_wait_sof &&
		tick_timeout(&telemetry_rx_tick, TELEMETRY_RX_FRAME_TIMEOUT_MS))
	{
		/* A dropped partial frame must not prevent the ESP from retrying three
		 * seconds later.  Do not use SOF as an escape byte: 0xA5 is valid data
		 * inside the raw app.py EEPROM image. */
		telemetry_reset_rx_parser();
	}

	while (telemetry_rx_pop(&value))
	{
		telemetry_process_command_byte(value);
	}
	if (telemetry_command_pending)
	{
		telemetry_finish_config_command();
	}
}

static void telemetry_maybe_reset(void)
{
	uint8_t saved_ea;
	uint8_t should_reset;

	saved_ea = EA;
	EA = 0;
	should_reset = telemetry_reset_ready;
	telemetry_reset_ready = 0;
	EA = saved_ea;
	if (should_reset)
	{
		/* The OK response has fully left UART1; reboot to apply every setting
		 * exactly as an app.py Save & Flash operation does. */
		reboot_system_AP_ROM();
	}
}

void telemetry_init(void)
{
	uint8_t saved_ea;
	uint8_t saved_sfrs;

	saved_ea = EA;
	EA = 0;
	saved_sfrs = SFRS;

	/* P1.6 is UART1 TX / ICE_DAT.  P0.2 is UART1 RX / ICE_CLK. */
	SFRS = 0;
	P1 |= SET_BIT6;
	P1M1 &= CLR_BIT6;
	P1M2 &= CLR_BIT6;
	P0 |= SET_BIT2;
	P0M1 |= SET_BIT2;
	P0M2 &= CLR_BIT2;

	/* UART1 TXD=P1.6 and RXD=P0.2: AUXR2[3:0] = 0101b.
	 * This is the same routing as ENABLE_UART1_TXD_P16 and
	 * ENABLE_UART1_RXD_P02 in the MS51 BSP. */
	SFRS = 2;
	AUXR2 = (AUXR2 & 0xF0) | 0x05;

	/* UART1 uses Timer3 directly; BRCK controls UART0 only.  Keep the
	 * proven timing used on this board, stopping Timer3 before loading it. */
	SFRS = 0;
	T3CON = 0x80;
	RH3 = 0xFF;
	RL3 = 0xF7;
	SCON_1 = 0x50; /* SM1=1, REN1=1: full-duplex UART1. */
	TI_1 = 0;
	RI_1 = 0;
	T3CON = 0x88;
	EIE1 |= SET_BIT0; /* UART1 RX interrupt must remain enabled while idle. */

	telemetry_tx_busy = 0;
	telemetry_tx_index = 0;
	telemetry_tx_length = 0;
	telemetry_buffer_overflow = 0;
	telemetry_rx_head = 0;
	telemetry_rx_tail = 0;
	telemetry_rx_overflow = 0;
	telemetry_command_pending = 0;
	telemetry_pending_command = 0;
	telemetry_pending_sequence = 0;
	telemetry_response_pending = 0;
	telemetry_response_with_config = 0;
	telemetry_reset_after_response = 0;
	telemetry_reset_after_tx = 0;
	telemetry_reset_ready = 0;
	telemetry_reset_rx_parser();
	telemetry_tick = 0;
	telemetry_rx_tick = 0;
	SFRS = saved_sfrs;
	EA = saved_ea;
}

void telemetry_update(void)
{
	telemetry_process_rx();
	telemetry_maybe_reset();

	if (!telemetry_tx_busy && telemetry_response_pending)
	{
		telemetry_build_config_response();
		telemetry_response_pending = 0;
		telemetry_reset_after_tx = telemetry_reset_after_response;
		telemetry_reset_after_response = 0;
		telemetry_start_transmit();
		return;
	}
	if (telemetry_tx_busy)
		return;
	if (!tick_timeout(&telemetry_tick, TELEMETRY_INTERVAL_MS))
		return;

	telemetry_build_snapshot();
	telemetry_start_transmit();
}

static void telemetry_rx_enqueue(uint8_t value)
{
	uint8_t next = telemetry_rx_head + 1;
	if (next == telemetry_rx_tail)
	{
		telemetry_rx_overflow = 1;
		return;
	}
	telemetry_rx_buffer[telemetry_rx_head] = value;
	telemetry_rx_head = next;
}

void telemetry_UART1_ISR(void) interrupt INT_NO_UART1
{
	uint8_t saved_sfrs;

	saved_sfrs = SFRS;
	SFRS = 0;

	/* RX and TX can both be pending in full-duplex mode. */
	if (RI_1)
	{
		const uint8_t received = SBUF_1;
		RI_1 = 0;
		telemetry_rx_enqueue(received);
	}
	if (TI_1)
	{
		TI_1 = 0;
		if (telemetry_tx_index < telemetry_tx_length)
		{
			SBUF_1 = telemetry_tx_buffer[telemetry_tx_index++];
		}
		else
		{
			telemetry_tx_busy = 0;
			if (telemetry_reset_after_tx)
			{
				telemetry_reset_after_tx = 0;
				telemetry_reset_ready = 1;
			}
		}
	}

	SFRS = saved_sfrs;
}

#endif
