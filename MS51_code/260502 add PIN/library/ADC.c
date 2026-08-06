#ifndef _ADC_C_
#define _ADC_C_
#include "ADC.h"

void READ_BAND_GAP(void) {
	uint8_t  Band_gap_Mark  = 0;
	uint8_t  Band_gap_High  = 0;
	uint8_t  Band_gap_Low   = 0;
	uint16_t Band_gap_Value = 0;

	bool temp = EA;
	EA        = 0;
	TA_protected;
	CHPCON |= SET_BIT0;
	Band_gap_Low  = IAP_Read_Byte(READ_UID, 0x0D); // Band gap Low
	Band_gap_Mark = Band_gap_Low & 0xF0;

	if (Band_gap_Mark == 0x80 || Band_gap_Mark == 0x00) {
		Band_gap_Low   = Band_gap_Low & 0x0F;
		Band_gap_High  = IAP_Read_Byte(READ_UID, 0x0C); // Bang gap High
		Band_gap_Value = (Band_gap_High << 4) + Band_gap_Low;
		Band_gap_Volt  = Band_gap_Value * 3 / 4;
		if (Band_gap_Mark == 0x80) Band_gap_Volt = Band_gap_Volt - 33; // the actually banggap voltage value is similar this value.
	}
	if (Band_gap_Mark == 0x90) {
		Band_gap_High  = IAP_Read_Byte(READ_UID, 0x0E); // Bang gap High
		Band_gap_Low   = IAP_Read_Byte(READ_UID, 0x0F); // Band gap Low
		Band_gap_Low   = Band_gap_Low & 0x0F;
		Band_gap_Value = (Band_gap_High << 4) + Band_gap_Low;
		Band_gap_Volt  = Band_gap_Value * 3 / 4;
	}

	TA_protected;
	CHPCON &= ~SET_BIT0;
	EA = temp;
}

void ADC_Set_Channel(uint8_t channel) {
	channel &= 0x0F;
	ADCCON0 |= channel;
	channel |= 0xF0;
	ADCCON0 &= channel;
}

void ADC_Set_Enable(bool enabled) {
	if (enabled) ADCCON1 |= SET_BIT0;
	else
		ADCCON1 &= CLR_BIT0;
}

void ADC_Start(void) {
	ADCF = 0; // clear ADC flag
	ADCS = 1; // start ADC conversion
}

uint16_t Read_ADC(uint8_t channel, uint8_t Clock_DIV) {
	ADC_Set_Channel(channel);
	CKDIV = Clock_DIV;
	ADC_Start();
	while (0 == ADCF)
		;
	CKDIV = 0x00;
	return (ADCRH << 4) | ADCRL;
}

void Read_ADC_Array(uint16_t *dat, uint8_t length, uint8_t channel, uint8_t Clock_DIV) {
	uint8_t i = 0;
	ADC_Set_Channel(channel);
	CKDIV = Clock_DIV;
	for (i = 0; i < length; i++, dat++) {
		ADC_Start();
		while (0 == ADCF)
			;
		*dat = (ADCRH << 4) | ADCRL;
	}
	CKDIV = 0x00;
}

uint16_t Get_VDD() {
	uint8_t  i;
	uint32_t VDD;

	uint16_t ADC_Sum = 0;
	uint16_t ADC_Band_Gap[5];

	Read_ADC_Array(&ADC_Band_Gap, 5, Band_Gap, 2);

	for (i = 2; i < 5; i++) // use the last 3 times data to make average
	{
		ADC_Sum += ADC_Band_Gap[i];
	}
	ADC_Sum /= 3;
	VDD = Band_gap_Volt;
	VDD = 0x1000 * VDD / ADC_Sum;
	return VDD & 0xFFFF;
}

uint16_t Convert_VDD(uint16_t adc) {
	uint32_t VDD;
	VDD = Band_gap_Volt;
	VDD = 0x1000 * VDD / adc;
	return VDD & 0xFFFF;
}
#endif
