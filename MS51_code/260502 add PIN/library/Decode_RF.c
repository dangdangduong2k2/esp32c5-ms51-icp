#ifndef _Decode_RF_C
#define _Decode_RF_C
#include "Decode_RF.h"

void Capture_ISR(void) interrupt 12 {
	bool           state          = false;
	uint8_t        alpha_32       = 0;
	static uint8_t dataPluseIndex = 0;
	static uint8_t temp_bit       = 0;
	static uint8_t temp_index_bit = 0;
	uint16_t       pluse_temp     = 0;

	// clr_CAPF0;
	CAPCON0 &= ~SET_BIT0;
	state      = !P04;
	pluse_temp = (C0H << 8) | C0L;

	if (dataPluseIndex > 1) { // get data
		//
		if (temp_index_bit % 2 == 0) {
			if (pluse_temp >= alpha * 2) { temp_bit = Pluse_Long; }
			else { temp_bit = Pluse_Short; }
		}
		else {
			if (pluse_temp >= alpha * 2 && temp_bit == Pluse_Short) { dataRF_temp = dataRF_temp * 2; }
			else if (pluse_temp <= alpha * 2 && temp_bit == Pluse_Long) { dataRF_temp = dataRF_temp * 2 + 1; }
			else {
				dataPluseIndex = 0;
				goto end;
			}
			dataPluseIndex++;
		}
		temp_index_bit++;
	}

	if (dataPluseIndex == 1) {
		if (!state) {
			alpha_32 = pluse_temp / alpha;
			if (alpha_32 <= 40 && alpha_32 >= 20) { // pluse sync to do get data 26 => 35
				//
				dataPluseIndex = 2;
				dataRF_temp    = 0;
				temp_bit       = 1;
				temp_index_bit = 0;
			}
		}
		else
			dataPluseIndex = 0;
	}

	if (dataPluseIndex == 0 && state) {
		if (pluse_temp > 200 && pluse_temp < 625) {
			alpha          = pluse_temp;
			dataPluseIndex = 1;
		}
	}

	if (dataPluseIndex == 26) {
		dataPluseIndex = 0;
		dataRF         = dataRF_temp;
		RF_Done        = true;
	}
end:
	// clr_TF2;
	TF2 = 0;
}

// void Learn_FR(RF_Type *RF) {
// 	bool    learn = true;
// 	uint8_t i     = 0;
// 	uint8_t j     = 0;
// 	RF_Type RF_Temp;
// 	RF_Type RF_Old;
// 	Led_RF = 1;
// 	do {
// 		if (RF_Done) {
// 			RF_Temp.Data = dataRF;
// 			RF_Done      = false;
// 			if (RF_Temp.Data != RF_Old.Data) {
// 				RF_Old.Data = RF_Temp.Data;
// 				i           = 0;
// 			}
// 			else {
// 				if (++i > 4) {
// 					learn    = false;
// 					RF->Data = RF_Temp.Data;
// 					Led_RF   = 0;
// 					Buzzer_Set_Run(3, 200);
// 					RF_Done = false;
// 				}
// 			}
// 		}
// 		delay_ms(10);
// 		if (++j > 49) {
// 			j = 0;
// 			Led_RF ^= 1;
// 		}
// 		set_system_WDCLR();
// 	} while (learn);
// 	Led_RF    = 1;
// 	Buzzer_RF = 0;
// }

void Init_Decode_RF() {
	// P04_Quasi_Mode;
	P0M1 &= ~SET_BIT4;
	P0M2 &= ~SET_BIT4;
	P04 = 1;

	// TIMER2_CAP0_Capture_Mode;
	T2CON &= ~SET_BIT0;
	T2MOD = 0x89;
	// IC3_P04_CAP0_BothEdge_Capture;
	CAPCON0 |= SET_BIT4;
	CAPCON1 &= 0xFC;
	CAPCON1 |= 0x02;
	CAPCON2 |= SET_BIT4;
	CAPCON3 &= 0xF0;
	CAPCON3 |= 0x04;

	// clock /16
	// clr_T2DIV0;
	// set_T2DIV1;
	// clr_T2DIV2;
	T2MOD &= ~SET_BIT4;
	T2MOD |= SET_BIT5;
	T2MOD &= ~SET_BIT6;

	// set_ECAP;
	EIE |= SET_BIT2;
	// set_TR2;
	TR2 = 1;
}

void RF_Run(RF_Type *RF_Data) {
	if (RF_Done) {
		RF_Data->Data = dataRF;
		RF_Done       = false;
		return;
	}
	RF_Data->Data = 0;
}
#endif
