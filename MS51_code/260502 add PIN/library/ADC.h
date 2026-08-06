#ifndef _ADC_H_
#define _ADC_H_

#define AIN0     0 // P17
#define AIN1     1 // P30
#define AIN2     2 // P07
#define AIN3     3 // P06
#define AIN4     4 // P05
#define AIN5     5 // P04
#define AIN6     6 // P03
#define AIN7     7 // P11
#define Band_Gap 8

#ifndef READ_UID
#define READ_UID 0x04
#endif

void     READ_BAND_GAP(void);
void     ADC_Set_Channel(uint8_t channel);
void     ADC_Set_Enable(bool enabled);
void     ADC_Start(void);
uint16_t Read_ADC(uint8_t channel, uint8_t Clock_DIV);
void     Read_ADC_Array(uint16_t *dat, uint8_t length, uint8_t channel, uint8_t Clock_Div);
uint16_t Get_VDD(void);
uint16_t Convert_VDD(uint16_t adc);

uint16_t Band_gap_Volt = 0;

#endif
