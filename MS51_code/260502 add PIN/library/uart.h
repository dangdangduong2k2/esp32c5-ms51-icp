#ifndef _UART_H_
#define _UART_H_

#define _USE_UART_0
#define _USE_UART_1

#define BUFFER_SIZE   32
#define Time_Out_Read 3 // 3 x timer 1 (2mS) = 6 mS

typedef struct _UART {
	uint8_t BUFF[BUFFER_SIZE];
	uint8_t Start;
	uint8_t End;
} Type_UART;

// typedef struct _UART {
// 	uint8_t *BUFF;
// 	uint8_t Start;
// 	uint8_t End;
// } Type_UART;

// uint8_t xdata buff_u0[128];
// uint8_t xdata buff_u1[64];

char xdata Temp_String[BUFFER_SIZE + 1];

#ifdef _USE_UART_0
Type_UART xdata BUFF_UART0; // = {buff_u0, 0, 0};
uint8_t         Time_Out_Read_UART0 = 5;

void Init_UART0_115200_F166(void);
void Send_Byte_UART0(uint8_t c);
void Send_Data_UART0(uint8_t *a);
void put_0(uint8_t value);
#endif

#ifdef _USE_UART_1
Type_UART xdata BUFF_UART1; // = {buff_u1, 0, 0};
uint8_t         Time_Out_Read_UART1 = 5;

void Init_UART1_115200_F166(void);
void Send_Byte_UART1(uint8_t c);
void Send_Data_UART1(uint8_t *a);
void put_1(uint8_t value);
#endif

uint8_t available(Type_UART *uart);
uint8_t Uart_Read(Type_UART *uart);

#endif
