#if defined(DEBUG) && !defined(MS51_DISABLE_LEGACY_SOFT_UART)

#define SUARTMAXBUF 8
#define SUARTTX_PIN IO16

uint8_t       uart_buff[SUARTMAXBUF];
const uint8_t digs[] = "0123456789abcdef";

#define dl_unit 104 // us  baud 9600
#define F_CPU   16600000

void uart_delay(void) {
	clr_CKCON_T0M; // T0M=0, Timer0 Clock = Fsys/12
	TMOD |= 0x01;  // Timer0 is 16-bit mode

	TL0 = LOBYTE(65535 - ((F_CPU / 1000000) * dl_unit / 12));
	TH0 = HIBYTE(65535 - ((F_CPU / 1000000) * dl_unit / 12));
	set_TCON_TR0; // Start Timer0
	while (TF0 != 1)
		; // Check Timer0 Time-Out Flag
	clr_TCON_TF0;

	clr_TCON_TR0; // Stop Timer0
}

void printnum(uint32_t u, uint8_t base, void (*putc)(uint8_t)) {
	char *p = &uart_buff[SUARTMAXBUF - 1];
	do {
		*p-- = digs[u % base];
		u /= base;
	} while (u != 0);

	while (++p != &uart_buff[SUARTMAXBUF])
		(*putc)(*p);
}

void WriteUART(uint8_t uartdata) {
	uint8_t bitcount = 0;
	PIN_low(SUARTTX_PIN); // start bit
	uart_delay();
	do {
		PIN_write(SUARTTX_PIN, uartdata & 0x01);
		uart_delay();
		uartdata >>= 1;
		bitcount++;
	} while (bitcount < 8);
	PIN_high(SUARTTX_PIN); // stop bit
	uart_delay();
}

void WriteUART_PRT(uint8_t *dt) {
	while (*dt) {
		WriteUART(*dt++);
	}
}

void SUART_INIT(void) {
	PIN_high(SUARTTX_PIN);
	PIN_input_PU(SUARTTX_PIN);
}
#define DBG_INIT()      SUART_INIT()
#define DBG_NUM(number) printnum(number, 10, WriteUART)
#define DBG_MSG         WriteUART_PRT

#else
#define DBG_INIT()
#define DBG_NUM(n)
#define DBG_MSG(m)
#endif
