#ifndef DATA_FLASH_Point_C_
#define DATA_FLASH_Point_C_
#include "Data_Flash_Point.h"

uint8_t AP_EEPROM_Read(uint16_t code *u16_addr) {
	uint8_t rdata;
	rdata = *u16_addr >> 8;
	return rdata;
}

void AP_EEPROM_Init(uint8_t *buff, uint16_t size) {
	bool     bit_EA    = false;
	uint8_t *ptr       = 0;
	uint16_t addr_page = 0;
	uint16_t i         = 0;

	bit_EA = EA;
	EA     = 0;

	if (buff != 0) {
		ptr         = buff;
		page_buffer = buff;
	}
	else
		ptr = page_buffer;
		
	if (size != 0) Size_Buffer = size;
	addr_page = Address_EEPROM;
	for (i = 0; i < Size_Buffer; i++, ptr++) {
		*ptr = AP_EEPROM_Read((uint16_t code *)(addr_page + i));
	}
	EA = bit_EA;
}

void AP_Erase_Page(uint16_t address, uint8_t page) {
	uint16_t addr = address;
	addr += page * 128;
	// Delete Page Flash
	IAPAL = addr & 0xff;
	IAPAH = (addr >> 8) & 0xff;
	IAPFD = 0xFF;

	// set_IAPEN;
	TA_protected;
	CHPCON |= SET_BIT0;

	// set_APUEN;
	TA_protected;
	IAPUEN |= SET_BIT0;

	IAPCN = 0x22;

	TA_protected;
	IAPTRG |= SET_BIT0;
	// Delete Page Flash
}

void AP_EEPROM_Commit(void) {
	bool     bit_EA    = false;
	uint8_t *ptr       = page_buffer;
	uint8_t  page      = Size_Buffer / 128;
	uint16_t addr_page = 0;
	uint16_t i         = 0;

	bit_EA = EA;
	EA     = 0;

	if (Size_Buffer == 0) return;
	page++;
	addr_page = Address_EEPROM;
	for (i = 0; i < page; i++) {
		AP_Erase_Page(addr_page, i);
	}

	// Save Data To Page Flash
	// set_IAPEN;
	TA_protected;
	CHPCON |= SET_BIT0;

	// set_APUEN;
	TA_protected;
	IAPUEN |= SET_BIT0;

	IAPCN = 0x21;
	for (i = 0; i < Size_Buffer; i++, ptr++) {
		IAPAL = (addr_page & 0xff) + i;
		IAPAH = (addr_page >> 8) & 0xff;
		IAPFD = *ptr;
		// set_IAPGO;
		TA_protected;
		IAPTRG |= SET_BIT0;
		Clear_WDT();
	}
	// clr_APUEN;
	TA_protected;
	IAPUEN &= ~SET_BIT0;

	// clr_IAPEN;
	TA_protected;
	CHPCON &= ~SET_BIT0;
	// Save Data To Page Flash

	// Read Data Flash To XRAM
	AP_EEPROM_Init(0, 0);

	EA = bit_EA;
}

#endif
