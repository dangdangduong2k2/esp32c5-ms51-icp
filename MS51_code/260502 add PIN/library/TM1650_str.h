#ifndef _TM1650_H_
#define _TM1650_H_
#include "bit.c"
#include "I2C_SW.c"

#define Brightness_LV1 0x10
#define Brightness_LV2 0x20
#define Brightness_LV3 0x30
#define Brightness_LV4 0x40
#define Brightness_LV5 0x50
#define Brightness_LV6 0x60
#define Brightness_LV7 0x70
#define Brightness_LV8 0x00

#define No_Dot 0x00
#define Dot_1  0x01
#define Dot_2  0x02
#define Dot_3  0x04
#define Dot_4  0x08

const uint8_t led_code[10] = {0x3F, 0X06, 0X5B, 0x4F, 0X66, 0X6D, 0X7D, 0X07, 0X7F, 0X6F};

typedef struct TM1650_Config {
	uint8_t        display;
	uint8_t        meaningless_zero;
	uint8_t        brightness;
	I2C_Config_Pin I2C;
} TM1650_Config;

void    TM1650_Set_Config(TM1650_Config *TM);
void    TM1650_Set_Display(TM1650_Config *TM, bool enable_display);
void    TM1650_Set_Bright(TM1650_Config *TM, uint8_t brightness);
void    TM1650_INIT(TM1650_Config *TM);
uint8_t TM1650_Read(TM1650_Config *TM, uint8_t *ack);
void    TM1650_Write(TM1650_Config *TM, uint8_t addr, uint8_t dat);
void    TM1650_write_nByte(TM1650_Config *TM, uint8_t *dat);
void    TM1650_display(TM1650_Config *TM, uint16_t number);
#endif
