#ifndef __Header_H_
#define __Header_H_

// #define DEBUG
#define btnTotal 3

/* P1.6 is the physical ICE_DAT wire.  Telemetry owns it through UART1, so
 * the legacy bit-banged debug UART must never be enabled on this pin. */
#define MS51_DISABLE_LEGACY_SOFT_UART 1

#define HIS_MAX 30 

typedef enum Progress_t
{
	CL,
	OP
} Progress_t;
typedef enum mode_t
{
	LCD,
	LED
} mode_t;
typedef enum
{
	LED_GREEN,
	LED_RED
};
typedef enum
{
	ICE_FLUSH_OP_TIME, //minute/second
	HIDE_MODE_DF,
	HIDE_MODE_END,
	HIDE_MODE_SL,	
	HIDE_MODE_HCF,
	HIDE_MODE_HDF,
	TRY_TIME_UNIT //days/hours
};
#define eeprom_init_val 2
struct eeprom_type
{
	uint8_t init;
	uint32_t time[2][2]; // 1 minute unit
	uint8_t delayST;	 // 100ms Unit
	// adv_setting
	uint8_t ST_LED;					// relay 2 ON, 100ms unit
	uint8_t DF;					// relay 3 ON, 0.1 min unit, ex: 25 -> 2.5 min
	uint8_t END;				// allow RL3 to turn on 15 seconds before OP event stops
	Progress_t first_progress1; // OP/ CL
	Progress_t first_progress2; // OP/ CL
	mode_t mode;				// LCD, LED
	uint16_t lock_time;
	uint8_t led_bri[2];
	uint8_t HCF;
	uint8_t hDF;
	uint8_t on_time;
	uint8_t touch_num;
	uint16_t try_time;
	uint16_t HCF_time;
	uint16_t time_delay;
	uint8_t check_box[7];
	uint8_t shutdown;
	uint32_t tried_time;
	uint8_t sig_index;
	uint32_t sig_count[30];
	uint16_t time_min[3][2];
	uint16_t time_max[3][2];
	uint8_t  hide_st;
	uint8_t ST_LCD;	
	uint16_t pin_password;
	uint16_t pin_period_day;
	uint16_t pin_day_count;
	uint8_t pin_request;
	uint16_t pin_backup_password;
	uint8_t pin_locked;
	uint8_t pin_backup_used;
	uint16_t pin_backup_period_day;
	uint8_t pin_period_unit; // 0: hours, 1: days
	uint8_t pin_backup_period_unit; // 0: hours, 1: days
	uint8_t pin_enable;
	uint8_t pin_period_show;
	uint8_t pin_wrong_limit;
	uint8_t pin_request_mark;
	uint8_t pin_request_mark_inv;
} eData;

#include "TM1650_multi.h"
#include "debug.h"
#include "gpio_manager.c"
#include "interrupt_manager.h"
#include "button_lib.c"
#include "TM1650_multi.c"
#include "global_variables.h"

#endif
