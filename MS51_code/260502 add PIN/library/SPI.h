#define SPI_CLOCK 0
/*
SPI_CLOCK = 0 8Mpbs
SPI_CLOCK = 1 4Mpbs
SPI_CLOCK = 2 2Mpbs
SPI_CLOCK = 3 1Mpbs
*/

// #define MOSI    P00
// #define MISO    P01
// #define CLK     P10

void SPI_Initial(void) {
#if SPI_CLOCK == 0
	clr_SPR1;
	clr_SPR0;
#elif SPI_CLOCK == 1
	clr_SPR1;
	set_SPR0;
#elif SPI_CLOCK == 2
	set_SPR1;
	clr_SPR0;
#elif SPI_CLOCK == 3
	set_SPR1;
	set_SPR0;
#endif

	/* /SS General purpose I/O ( No Mode Fault ) */
	set_DISMODF;
	clr_SSOE;

	/* SPI in Master mode */
	set_MSTR;

	/* MSB first */
	clr_LSBFE;

	clr_CPOL;
	clr_CPHA;

	/* Enable SPI function */
	set_SPIEN;
}

// void spi_setPin(bit SS) { _SS = SS; }
// void spi_begin_tranfer() {
// 	spi_setPin(P11);
// 	SS = 0;
// }

uint8_t spi_transfer(uint8_t dat) {
	uint8_t u8dat = 0;
	SPDR          = dat;
	while ((SPSR & 0x80) == 0x00)
		;
	clr_SPIF;
	u8dat = SPDR;
	clr_SPIF;
	return u8dat;
}