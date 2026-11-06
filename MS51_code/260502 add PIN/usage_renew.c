#ifndef MS51_USAGE_RENEW_C
#define MS51_USAGE_RENEW_C
#define USAGE_RECEIPT_MARK 0xA9u
#define USAGE_ENVELOPE_SIZE 217u
#ifndef USAGE_READ_SAVED
#define USAGE_READ_SAVED(i) AP_EEPROM_Read((uint16_t code *)(Address_Flash + (i)))
#endif
typedef char usage_eeprom_fits[(sizeof(eData) < 256u) ? 1 : -1];
static uint8_t usage_reset_required;
static uint8_t xdata usage_old_token[16];

/* Special SET envelope, never copied over the ordinary configuration. An old
 * firmware rejects its 0xE3 version safely. CRC is checked by the UART parser. */
static uint8_t usage_renew_apply(const uint8_t xdata *blob)
{
    uint8_t i, any, not_ff, same, old_unit, old_shutdown, old_mark;
    uint16_t period, old_period, old_timeout;
    uint32_t old_elapsed;
    uint8_t xdata *bytes = (uint8_t xdata *)&eData;
    usage_reset_required = 0;
    if (blob[0] != 0xE3 || blob[1] != 'T' || blob[2] != 'R' ||
        blob[3] != 'Y' || blob[4] != '1' || eData.init != eeprom_init_val) return 0;
    period = ((uint16_t)blob[5] << 8) | blob[6];
    /* Zero removes the TRY limit, including an already expired shutdown. */
    if (period > 999 || blob[7] > 1) return 0;
    any = not_ff = 0;
    for (i = 8; i < 24; ++i) { any |= blob[i]; not_ff |= (uint8_t)(blob[i] ^ 0xFF); }
    if (!any || !not_ff) return 0;
    for (i = 24; i < USAGE_ENVELOPE_SIZE; ++i) if (blob[i]) return 0;
    same = eData.usage_mark == USAGE_RECEIPT_MARK;
    for (i = 0; i < 16; ++i) if (eData.usage_token[i] != blob[8+i]) same = 0;
    if (same) return 2; /* Receipt survives restart; never restart the period twice. */
    old_period = eData.try_time; old_unit = eData.check_box[TRY_TIME_UNIT];
    old_shutdown = eData.shutdown; old_elapsed = eData.tried_time;
    old_timeout = shutdown_timeout; old_mark = eData.usage_mark;
    for (i = 0; i < 16; ++i) usage_old_token[i] = eData.usage_token[i];
    eData.try_time = period; eData.check_box[TRY_TIME_UNIT] = blob[7];
    eData.tried_time = 0; eData.shutdown = 0;
    eData.usage_mark = USAGE_RECEIPT_MARK;
    for (i = 0; i < 16; ++i) eData.usage_token[i] = blob[8+i];
    AP_EEPROM_Commit();
    for (i = 0; i < sizeof(eData); ++i) if (USAGE_READ_SAVED(i) != bytes[i]) break;
    if (i != sizeof(eData)) {
        eData.try_time = old_period; eData.check_box[TRY_TIME_UNIT] = old_unit;
        eData.shutdown = old_shutdown; eData.tried_time = old_elapsed;
        eData.usage_mark = old_mark;
        for (i = 0; i < 16; ++i) eData.usage_token[i] = usage_old_token[i];
        shutdown_timeout = old_timeout;
        return 0;
    }
    shutdown_timeout_refresh();
    usage_reset_required = old_shutdown ? 1 : 0;
    return 1;
}
#endif
