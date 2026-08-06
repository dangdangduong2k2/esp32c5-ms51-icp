// registers
#define REG_FIFO                 0x00
#define REG_OP_MODE              0x01
#define REG_FRF_MSB              0x06
#define REG_FRF_MID              0x07
#define REG_FRF_LSB              0x08
#define REG_PA_CONFIG            0x09
#define REG_OCP                  0x0b
#define REG_LNA                  0x0c
#define REG_FIFO_ADDR_PTR        0x0d
#define REG_FIFO_TX_BASE_ADDR    0x0e
#define REG_FIFO_RX_BASE_ADDR    0x0f
#define REG_FIFO_RX_CURRENT_ADDR 0x10
#define REG_IRQ_FLAGS            0x12
#define REG_RX_NB_BYTES          0x13
#define REG_PKT_SNR_VALUE        0x19
#define REG_PKT_RSSI_VALUE       0x1a
#define REG_RSSI_VALUE           0x1b
#define REG_MODEM_CONFIG_1       0x1d
#define REG_MODEM_CONFIG_2       0x1e
#define REG_PREAMBLE_MSB         0x20
#define REG_PREAMBLE_LSB         0x21
#define REG_PAYLOAD_LENGTH       0x22
#define REG_MODEM_CONFIG_3       0x26
#define REG_FREQ_ERROR_MSB       0x28
#define REG_FREQ_ERROR_MID       0x29
#define REG_FREQ_ERROR_LSB       0x2a
#define REG_RSSI_WIDEBAND        0x2c
#define REG_DETECTION_OPTIMIZE   0x31
#define REG_INVERTIQ             0x33
#define REG_DETECTION_THRESHOLD  0x37
#define REG_SYNC_WORD            0x39
#define REG_INVERTIQ2            0x3b
#define REG_DIO_MAPPING_1        0x40
#define REG_VERSION              0x42
#define REG_PA_DAC               0x4d

// modes
#define MODE_LONG_RANGE_MODE 0x80
#define MODE_SLEEP           0x00
#define MODE_STDBY           0x01
#define MODE_TX              0x03
#define MODE_RX_CONTINUOUS   0x05
#define MODE_RX_SINGLE       0x06

// PA config
#define PA_BOOST 0x80

// IRQ masks
#define IRQ_TX_DONE_MASK           0x08
#define IRQ_PAYLOAD_CRC_ERROR_MASK 0x20
#define IRQ_RX_DONE_MASK           0x40

#define RF_MID_BAND_THRESHOLD 525E6
#define RSSI_OFFSET_HF_PORT   157
#define RSSI_OFFSET_LF_PORT   164

#define MAX_PKT_LENGTH 255

#define frf433  7094272 // uint64_t frf = ((uint64_t)frequency << 19) / 32000000; ex: frequency = 433E6
// lora PIN
#define Lora_SS P11
#define DIO0    P12
#define RST     P13

int8_t xdata  rssi      = 0;
uint8_t xdata rssi_temp = 0;
uint8_t xdata data_temp[32];
uint8_t xdata data_encode[64];
uint8_t xdata data_encode_length = 0;
uint8_t xdata data_decode[64];
uint8_t xdata data_decode_length = 0;
uint8_t xdata data_receive[64];
uint8_t xdata data_receive_length = 0;
uint8_t       temp_for_lora       = 0;

uint8_t  _ss;
uint8_t  _reset;
uint8_t  _dio0;
uint32_t _frequency;
uint8_t  _implicitHeaderMode;
uint8_t  _packetIndex;

void delay_ms(UINT16 t) {
	UINT16 i, j;
	for (i = 0; i < t; i++) {
		for (j = 0; j < 1000; j++) {
			nop;
			nop;
		}
	}
}

uint8_t Lora_singleTransfer(uint8_t address, uint8_t value) {
	uint8_t u8data = 0;
	Lora_SS        = 0;
	spi_transfer(address);
	u8data  = spi_transfer(value);
	Lora_SS = 1;
	return u8data;
}
void Lora_writeRegister(uint8_t address, uint8_t value) { Lora_singleTransfer(address | 0x80, value); }

uint8_t Lora_readRegister(uint8_t address) { return Lora_singleTransfer(address & 0x7f, 0x00); }

void Lora_setPins(uint8_t ss, uint8_t reset, uint8_t dio0) {
	_ss    = ss;
	_reset = reset;
	_dio0  = dio0;

	// output_drive(_ss);
	// output_drive(_reset);
	// output_float(_dio0);
}

void Lora_setFrequency(uint32_t frequency) {
	uint32_t frf = frequency;
	_frequency   = 433E6;
	Lora_writeRegister(REG_FRF_MSB, (uint8_t)(frf >> 16));
	Lora_writeRegister(REG_FRF_MID, (uint8_t)(frf >> 8));
	Lora_writeRegister(REG_FRF_LSB, (uint8_t)(frf >> 0));
}

void Lora_sleep() { Lora_writeRegister(REG_OP_MODE, MODE_LONG_RANGE_MODE | MODE_SLEEP); }

void Lora_idle() { Lora_writeRegister(REG_OP_MODE, MODE_LONG_RANGE_MODE | MODE_STDBY); }

void Lora_setOCP(uint8_t mA) {
	uint8_t ocpTrim = 27;

	if (mA <= 120) { ocpTrim = (mA - 45) / 5; }
	else if (mA <= 240) { ocpTrim = (mA + 30) / 10; }

	Lora_writeRegister(REG_OCP, 0x20 | (0x1F & ocpTrim));
}

void Lora_setTxPower(int level) {
	// PA BOOST
	if (level > 17) {
		if (level > 20) { level = 20; }

		// subtract 3 from level, so 18 - 20 maps to 15 - 17
		level -= 3;

		// High Power +20 dBm Operation (Semtech SX1276/77/78/79 5.4.3.)
		Lora_writeRegister(REG_PA_DAC, 0x87);
		Lora_setOCP(140);
	}
	else {
		if (level < 2) { level = 2; }
		// Default value PA_HF/LF or +17dBm
		Lora_writeRegister(REG_PA_DAC, 0x84);
		Lora_setOCP(100);
	}

	Lora_writeRegister(REG_PA_CONFIG, PA_BOOST | (level - 2));
}

void Lora_implicitHeaderMode() {
	_implicitHeaderMode = 1;

	Lora_writeRegister(REG_MODEM_CONFIG_1, Lora_readRegister(REG_MODEM_CONFIG_1) | 0x01);
}

void Lora_explicitHeaderMode() {
	_implicitHeaderMode = 0;

	Lora_writeRegister(REG_MODEM_CONFIG_1, Lora_readRegister(REG_MODEM_CONFIG_1) & 0xfe);
}

int8_t Lora_packetRssi(void) {
	return (Lora_readRegister(REG_PKT_RSSI_VALUE) - (_frequency < RF_MID_BAND_THRESHOLD ? RSSI_OFFSET_LF_PORT : RSSI_OFFSET_HF_PORT));
}

uint8_t Lora_available(void) { return (Lora_readRegister(REG_RX_NB_BYTES) - _packetIndex); }

void Lora_receiver(uint8_t size) {
	Lora_writeRegister(REG_DIO_MAPPING_1, 0x00); // DIO0 => RXDONE

	if (size > 0) {
		Lora_implicitHeaderMode();

		Lora_writeRegister(REG_PAYLOAD_LENGTH, size & 0xff);
	}
	else { Lora_explicitHeaderMode(); }

	Lora_writeRegister(REG_OP_MODE, MODE_LONG_RANGE_MODE | MODE_RX_CONTINUOUS);
}

uint8_t Lora_read() {
	if (!Lora_available()) { return -1; }

	_packetIndex++;

	return Lora_readRegister(REG_FIFO);
}

void onReceive(uint8_t packetSize) {
	int i = 0;
	// received a packet

	// read packet
	for (i = 0; i < packetSize; i++) {
		data_receive[i] = Lora_read();
	}

	// print RSSI of packet
	rssi                = Lora_packetRssi();
	data_receive_length = packetSize;
}

void Lora_handleDio0Rise(void) {
	int irqFlags = Lora_readRegister(REG_IRQ_FLAGS);
	int packetLength;
	// clear IRQ's
	Lora_writeRegister(REG_IRQ_FLAGS, irqFlags);

	if ((irqFlags & IRQ_PAYLOAD_CRC_ERROR_MASK) == 0) {

		if ((irqFlags & IRQ_RX_DONE_MASK) != 0) {
			// received a packet
			_packetIndex = 0;

			// read packet length
			packetLength = _implicitHeaderMode ? Lora_readRegister(REG_PAYLOAD_LENGTH) : Lora_readRegister(REG_RX_NB_BYTES);

			// set FIFO address to current RX address
			Lora_writeRegister(REG_FIFO_ADDR_PTR, Lora_readRegister(REG_FIFO_RX_CURRENT_ADDR));

			// if (_onReceive) { _onReceive(packetLength); }
			onReceive(packetLength);
		}
		else if ((irqFlags & IRQ_TX_DONE_MASK) != 0) {
			// if (_onTxDone) { _onTxDone(); }
		}
	}
}

void Lora_onDio0Rise(void) { Lora_handleDio0Rise(); }

uint8_t Lora_isTransmitting() {
	if ((Lora_readRegister(REG_OP_MODE) & MODE_TX) == MODE_TX) { return 1; }

	if (Lora_readRegister(REG_IRQ_FLAGS) & IRQ_TX_DONE_MASK) {
		// clear IRQ's
		Lora_writeRegister(REG_IRQ_FLAGS, IRQ_TX_DONE_MASK);
	}

	return 0;
}

uint8_t Lora_beginPacket(int implicitHeader) {
	if (Lora_isTransmitting()) { return 0; }

	// put in standby mode
	Lora_idle();

	if (implicitHeader) { Lora_implicitHeaderMode(); }
	else { Lora_explicitHeaderMode(); }

	// reset FIFO address and paload length
	Lora_writeRegister(REG_FIFO_ADDR_PTR, 0);
	Lora_writeRegister(REG_PAYLOAD_LENGTH, 0);

	return 1;
}

uint8_t Lora_endPacket(uint8_t async) {

	// if ((async) && (_onTxDone)) Lora_writeRegister(REG_DIO_MAPPING_1, 0x40); // DIO0 => TXDONE; not use TX done

	// put in TX mode
	Lora_writeRegister(REG_OP_MODE, MODE_LONG_RANGE_MODE | MODE_TX);

	if (!async) {
		// wait for TX done
		while ((Lora_readRegister(REG_IRQ_FLAGS) & IRQ_TX_DONE_MASK) == 0) {
			// reset WDT
		}
		// clear IRQ's
		Lora_writeRegister(REG_IRQ_FLAGS, IRQ_TX_DONE_MASK);
	}

	return 1;
}

uint16_t Lora_write_buff(char *buffer, uint16_t size) {
	uint16_t i;
	uint16_t currentLength;
	currentLength = Lora_readRegister(REG_PAYLOAD_LENGTH);
	// check size
	if ((currentLength + size) > MAX_PKT_LENGTH) { size = MAX_PKT_LENGTH - currentLength; }

	// write data
	for (i = 0; i < size; i++) {
		Lora_writeRegister(REG_FIFO, buffer[i]);
	}

	// update length
	Lora_writeRegister(REG_PAYLOAD_LENGTH, currentLength + size);

	return size;
}

void Lora_write(char _byte) {
	//
	Lora_write_buff(&_byte, 1);
}

void Lora_write_str(char *str) {
	uint16_t size     = 0;
	char    *size_ptr = str;
	while (*size_ptr++) {
		size++;
	}

	Lora_write_buff(str, size);
}

uint8_t Lora_begin(uint32_t frequency) {
	uint8_t version;
	// set SS high
	// output_high(_ss);
	Lora_SS = 1;
	// perform reset
	RST     = 0; // output_low(_reset);
	delay_ms(10);
	RST = 1; // output_high(_reset);
	delay_ms(10);

	// check version
	version = Lora_readRegister(REG_VERSION);
	if (version != 0x12) { return 0; }

	Lora_sleep();
	Lora_setFrequency(frequency);

	// set base addresses
	Lora_writeRegister(REG_FIFO_TX_BASE_ADDR, 0);
	Lora_writeRegister(REG_FIFO_RX_BASE_ADDR, 0);

	// set LNA boost
	Lora_writeRegister(REG_LNA, Lora_readRegister(REG_LNA) | 0x03);

	// set auto AGC
	Lora_writeRegister(REG_MODEM_CONFIG_3, 0x04);

	// // set output power to 17 dBm
	Lora_setTxPower(17);

	// put in standby mode
	Lora_idle();
	return 1;
}

uint16_t size_data(char *dat) {
	uint16_t a        = 0;
	char    *size_ptr = dat;
	while (*size_ptr++) {
		a++;
	}
	return a;
}

void encode_data(char *dat) {
	uint8_t  n  = 0;
	uint16_t n2 = 0;
	uint8_t  i  = 0;
	uint8_t  a  = size_data(dat);

	for (i = 0; i < a; i++) {
		n = *dat++;
		n2 += n;
		data_encode[i] = (n + a);
	}
	data_encode[i]     = ((n2 % a) + a);
	data_encode_length = i + 1;
}

void decode_data() {
	uint8_t  n  = data_receive_length - 1;
	uint8_t  i  = 0;
	uint16_t n2 = 0;

	for (i = 0; i < n; i++) {
		n2 += data_receive[i];
		data_decode[i] = (data_receive[i] - n);
	}
	if (((n2 - data_receive[n]) % n) == 0) data_decode_length = i;
	else
		data_decode_length = 0;

	for (temp_for_lora = 0; temp_for_lora < data_decode_length; temp_for_lora++) {
		Send_Byte_UART0(data_decode[temp_for_lora]);
	}
}
