/*  TM1637 SINGLE Library
    Version: 1.3
    Date modified: 240925
    Author: Tran-Luyen
    Author Github: https://github.com/Tran-Luyen
    Github Link: https://github.com/Tran-Luyen/Code-Lib/blob/main/Common-lib/TM1637/TM1637-SINGLE
    File path: file:///D:\Works\Github_Projects\Code-Lib\Common-lib/TM1637/TM1637-SINGLE/TM1637.h
*/

#ifndef _TM1637_H_
#define _TM1637_H_

#include "gpio_manager.h"
#include "delay.h"
#include "common.h"
#include "MS51_GPIO_Macro.h"
#include "seg_map.h"
#include "header.h"



#ifndef TM_DL
#define TM_DL(t) delay_us(t)
#endif

#ifndef TM1637_SCL_PIN
#define TM1637_SCL_PIN SCL_LED_PIN
#endif

#ifndef TM1637_SDA_PIN
#define TM1637_SDA_PIN SDA_LED_PIN
#endif

#define TM1637_SCL PIN(TM1637_SCL_PIN)
#define TM1637_SDA PIN(TM1637_SDA_PIN)

typedef enum TM1637_CMD_t { // COMMAND
	TM1637_CMD_DISPLAY_SETTING = 0x01,
	TM1637_CMD_DATA_SETTING    = 0x02,
	TM1637_CMD_ADDR_SETTING    = 0x03
} TM1637_CMD_t;

typedef enum TM1637_DATA_CMD_t { // DATA COMMAND | TM1637_CMD_DATA_SETTING
	TM1637_CMD_WRITE_ADDR_AUTO  = 0x02,
	TM1637_CMD_WRITE_ADDR_FIXED = 0x22,
	TM1637_CMD_READ_ADDR_AUTO   = 0x42,
	TM1637_CMD_AUTO_ADDR_FIXED  = 0x62
} TM1637_DATA_CMD_t;

typedef enum TM1637_BRN_t { // BRIGHTNESS | TM1637_CMD_DISPLAY_SETTING
	TM1637_BRN_LV1 = 0x01,
	TM1637_BRN_LV2 = 0x81,
	TM1637_BRN_LV3 = 0x41,
	TM1637_BRN_LV4 = 0xC1,
	TM1637_BRN_LV5 = 0x21,
	TM1637_BRN_LV6 = 0xA1,
	TM1637_BRN_LV7 = 0x61,
	TM1637_BRN_LV8 = 0xE1
} TM1637_BRN_t;

typedef enum TM1637_ADDR_t { // address | TM1637_CMD_ADDR_SETTING
	TM1637_ADDR_GRID1 = 0x03,
	TM1637_ADDR_GRID2 = 0x83,
	TM1637_ADDR_GRID3 = 0x43,
	TM1637_ADDR_GRID4 = 0xC3,
	TM1637_ADDR_GRID5 = 0x23,
	TM1637_ADDR_GRID6 = 0xA3
} TM1637_ADDR_t;

typedef enum TM1637_DP_MODE_t { TM1637_DP_OFF = 0x00, TM1637_DP_ON = 0x10 } TM1637_DP_MODE_t;

#endif