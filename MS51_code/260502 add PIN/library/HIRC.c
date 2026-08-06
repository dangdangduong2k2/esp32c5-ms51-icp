#ifndef _HIRC_C_
#define _HIRC_C_
#include "HIRC.h"

/* IAP commands used by Nuvoton's official MODIFY_HIRC implementation. */
#define HIRC_IAP_READ_UID 0x04
#define HIRC_IAP_READ_DID 0x0C

static uint8_t HIRC_read_iap_byte(uint8_t command, uint16_t address)
{
	IAPCN = command;
	IAPAH = (uint8_t)(address >> 8);
	IAPAL = (uint8_t)address;
	TA = 0xAA;
	TA = 0x55;
	IAPTRG |= SET_BIT0;
	return IAPFD;
}

void MODIFY_HIRC_166(void)
{
	uint8_t saved_ea;
	uint8_t saved_sfrs;
	/* Keep the RCTRIM write operands in direct DATA for the 4-clock TA window. */
	uint8_t data hirc_map_0;
	uint8_t data hirc_map_1;
	uint8_t process_id;
	uint8_t judge;
	uint8_t offset;
	uint16_t trim_value;

	/*
	 * Always reload the factory 16 MHz trim from UID before applying the
	 * 16.6 MHz offset. RCTRIM is reset on warm resets while PCON.POF is not,
	 * so the former POF-only path could silently leave the MCU at 16 MHz.
	 */
	saved_ea = EA;
	EA = 0;
	saved_sfrs = SFRS;
	SFRS = 0;

	TA = 0xAA;
	TA = 0x55;
	CHPCON |= SET_BIT0;

	hirc_map_0 = HIRC_read_iap_byte(HIRC_IAP_READ_UID, 0x0030);
	hirc_map_1 = HIRC_read_iap_byte(HIRC_IAP_READ_UID, 0x0031);
	trim_value = ((uint16_t)hirc_map_0 << 1) | (hirc_map_1 & 0x01);
	judge = (uint8_t)trim_value & 0xC0;
	offset = (uint8_t)trim_value & 0x3F;
	trim_value -= 14;

	/* Process correction copied from the official MS51 32K BSP. */
	process_id = HIRC_read_iap_byte(HIRC_IAP_READ_DID, 0x0001);
	if (process_id == 0x4B || process_id == 0x52 || process_id == 0x53)
	{
		if (offset < 15)
		{
			if (judge == 0x40 || judge == 0x80 || judge == 0xC0)
				trim_value -= 14;
		}
		else
		{
			trim_value -= 4;
		}
	}

	hirc_map_0 = (uint8_t)(trim_value >> 1);
	hirc_map_1 = (hirc_map_1 & 0xFE) | (uint8_t)(trim_value & 0x01);
	TA = 0xAA;
	TA = 0x55;
	RCTRIM0 = hirc_map_0;
	TA = 0xAA;
	TA = 0x55;
	RCTRIM1 = hirc_map_1;

	TA = 0xAA;
	TA = 0x55;
	CHPCON &= CLR_BIT0;
	PCON &= CLR_BIT4;
	SFRS = saved_sfrs;
	EA = saved_ea;
}
#endif
