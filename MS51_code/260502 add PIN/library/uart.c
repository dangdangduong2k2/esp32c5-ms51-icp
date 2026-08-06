#ifndef _UART_C_
#define _UART_C_
#include "uart.h"
#include "Function_System.c"

#ifdef _USE_UART_0
// UART 0
void Init_UART0_115200_F166(void) {
	P06_PushPull_Mode;
	P07_Quasi_Mode;
	memset_User((uint8_t *)&BUFF_UART0, 0, sizeof(BUFF_UART0));
	SCON = 0x52;      // UART0 Mode1,REN=1,TI=1
	PCON |= SET_BIT7; // Timer1 or Timer3 / 16

#ifndef _TIMER3_UART_BAUDRATE_115200_F166_
#define _TIMER3_UART_BAUDRATE_11500_F166_
	T3CON &= 0xF8;     // T3PS2=0,T3PS1=0,T3PS0=0(Prescale=1)
	T3CON |= SET_BIT5; // UART0 baud rate clock source = Timer3
	RH3 = 0xFF;
	RL3 = 0xF7;
	T3CON |= SET_BIT3; // Trigger Timer3 , Enable Timer3
#endif

	ES = 1; // For interrupt enable UART0
}

void Send_Byte_UART0(uint8_t c) {
	ES   = 0;
	TI   = 0;
	SBUF = c;
	while (TI == 0)
		;
	ES = 1;
}

void Send_Data_UART0(uint8_t *a) {
	while (*a) {
		Send_Byte_UART0(*a++);
	}
}

void SerialPort0_ISR(void) interrupt 4 {
	if (RI == 1) {
		RI = 0;
		put_0(SBUF);
		Time_Out_Read_UART0 = Time_Out_Read;
	}
	if (TI == 1) TI = 0;
}

void put_0(uint8_t value) {
	BUFF_UART0.BUFF[BUFF_UART0.End++] = value;
	BUFF_UART0.End %= BUFFER_SIZE;
	if (BUFF_UART0.End == BUFF_UART0.Start) {
		BUFF_UART0.Start++;
		BUFF_UART0.Start %= BUFFER_SIZE;
	}
}
// UART 0
#endif

#ifdef _USE_UART_1
// UART 1
void Init_UART1_115200_F166(void) {
	// P16_PushPull_Mode;
	P16_Quasi_Mode;
	P02_Quasi_Mode;
	P16 = 1;
	P02 = 1;
	memset_User((uint8_t *)&BUFF_UART1, 0, sizeof(BUFF_UART1));
	SCON_1 = 0x50; // UART1 Mode1,REN_1 = 1,TI_1 = 1

#ifndef _TIMER3_UART_BAUDRATE_115200_F166_
#define _TIMER3_UART_BAUDRATE_11500_F166_
	T3CON &= 0xF8;     // T3PS2=0,T3PS1=0,T3PS0=0(Prescale=1)
	T3CON |= SET_BIT5; // UART0 baud rate clock source = Timer3
	RH3 = 0xFF;
	RL3 = 0xF7;
	T3CON |= SET_BIT3; // Trigger Timer3 , Enable Timer3
#endif

	EIE1 |= SET_BIT0; // For interrupt enable UART1
}

void Send_Byte_UART1(uint8_t c) {
	EIE1 &= CLR_BIT0;
	TI_1   = 0;
	SBUF_1 = c;
	while (TI_1 == 0)
		;
	EIE1 |= SET_BIT0;
}

void Send_Data_UART1(uint8_t *a) {
	while (*a) {
		Send_Byte_UART1(*a++);
	}
}

void SerialPort1_ISR(void) interrupt 15 {
	if (RI_1 == 1) {
		RI_1 = 0;
		put_1(SBUF_1);
		Time_Out_Read_UART1 = Time_Out_Read;
	}
	if (TI_1 == 1) TI_1 = 0;
}

void put_1(uint8_t value) {
	BUFF_UART1.BUFF[BUFF_UART1.End++] = value;
	BUFF_UART1.End %= BUFFER_SIZE;
	if (BUFF_UART1.End == BUFF_UART1.Start) {
		BUFF_UART1.Start++;
		BUFF_UART1.Start %= BUFFER_SIZE;
	}
}
// UART 1
#endif

uint8_t available(Type_UART *uart) {
	if (uart->Start == uart->End) return 0;
	if (uart->Start < uart->End) return uart->End - uart->Start;
	else
		return BUFFER_SIZE + uart->End - uart->Start;
}

uint8_t Uart_Read(Type_UART *uart) {
	uint8_t item = 0;
	if (uart->Start == uart->End) return 0;
	item = uart->BUFF[uart->Start++];
	uart->Start %= BUFFER_SIZE;
	return item;
}

void Read_String(Type_UART *uart) {
	uint8_t *ptr = Temp_String;
	while (available(uart)) {
		*ptr++ = Uart_Read(uart);
	}
	*ptr = 0;
}

#endif
