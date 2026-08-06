#ifndef _Decode_RF_H
#define _Decode_RF_H
#define Pluse_Short 0
#define Pluse_Long  1

struct get_str {
	uint8_t Code_2;
	uint8_t Code_1;
	uint8_t Code_0;
	uint8_t Key;
};

typedef union RF_Type {
	uint32_t       Data;
	struct get_str Get;
} RF_Type;

bool RF_Done     = false;

uint16_t       alpha       = 0;
uint32_t       dataRF_temp = 0;
uint32_t xdata dataRF      = 0;

void Init_Decode_RF(void);
void RF_Run(RF_Type *RF_Data);
#endif
