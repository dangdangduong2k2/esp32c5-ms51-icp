#ifndef _interrupt_manager_h_
#define _interrupt_manager_h

#include "MS51_32K.h"
#include "delay.c"
#include "Function_System.c"
#include "MS51_GPIO_Macro.h"
#include "global_variables.h"

// void Timer2_INIT(void) {
// 	// Timer 2 Auto Reload
// 	// 1 mS - HIRC 16.6 MHz
// 	TH2    = 0;
// 	TL2    = 0;
// 	RCMP2H = 0xBF;
// 	RCMP2L = 0x28;
// 	T2MOD |= 0x88;
// 	CM_RL2 = 0;
// 	EIE |= SET_BIT7;
// 	TR2 = 1;
// }
// void Timer2_ISR(void) interrupt INT_NO_TMR2 {
// 	TF2 = 0; // clear TMR2 interrupt flag
//
// }

void Timer1_INIT() {
	// Timer 1 Mode 1
	// 1 mS - HIRC 16.6 MHz
	CKCON |= 0x10;
	TMOD &= 0x0F;
	TMOD |= 0x10;
	TH1 = 0xBF;
	TL1 = 0x28;
	ET1 = 1;
	TR1 = 1;
}
void Timer1_ISR(void) interrupt INT_NO_TMR1 {
	TH1 = 0xBF;
	TL1 = 0x28;
	// To do Code
	++millis_second;
}

// void Timer0_INIT() {
// 	// Timer 0 Mode 1
// 	// 30 mS - HIRC 16.6 MHz
// 	TMOD &= 0xF0;
// 	TMOD |= 0x01; // timer 0 mode 1, 16 bit
// 	TH0 = 0x00;
// 	TL0 = 0x00;
// 	// ET0 = 1; // enable timer0 interrupt
// 	TR0 = 1; // enable timer0
// }

// void Timer0_ISR(void) interrupt INT_NO_TMR0 {
// 	TH0 = 0x00;
// 	TL0 = 0x00;
// 	Do something
// }

// void EX1_ISR(void) interrupt INT_NO_INT1 {
// 	// Do something
// 	IE1 = 0;
// }

// void EX1_INIT(void) {
// 	IT1 = 1; // Enable falling edge triggered
// 	EX1 = 1; // Enable external interrupt 1
// }

// void EX0_ISR(void) interrupt INT_NO_INT0 {
// 	// Do something
// 	IE0            = 0; // clear INT0 interrupt flag
// }

// void EX0_INIT(void) {
// 	IT0 = 1; // Enable falling edge triggered
// 	EX0 = 1; // Enable external interrupt 1
// }

void ADC_ISR(void) interrupt INT_NO_ADC {
	ADCF                             = 0; // Clear ADC interrupt flag
	ADC_Result[adc_channel][adc_idx] = (ADCRH << 4) | ADCRL;
	if (++adc_idx >= adc_count_max) {
		adc_idx = 0;
		if (++adc_channel > 1) adc_channel = 0;
		ADC_Set_Channel(adc_channel_idx[adc_channel]);
	}
	adc_done = true;
}

#endif