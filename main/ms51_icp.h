#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

#define MS51FC0AE_DEVICE_ID UINT16_C(0x5332)
#define MS51FC0AE_PRODUCT_ID UINT16_C(0x0B00)
#define MS51FC0AE_PART_ID UINT32_C(0x0B005332)
#define MS51_NUVOTON_CID UINT8_C(0xDA)

#define MS51_FLASH_SIZE (32u * 1024u)
#define MS51_FLASH_PAGE_SIZE 128u
#define MS51_CONFIG_ADDRESS UINT32_C(0x30000)
#define MS51_CONFIG_SIZE 5u

typedef struct {
    uint16_t device_id;
    uint16_t product_id;
    uint8_t company_id;
} ms51_icp_identity_t;

/** Enter ICP mode and leave the three GPIOs owned by the driver. */
esp_err_t ms51_icp_open(ms51_icp_identity_t *identity);

/** Re-enter ICP without releasing the three pins, then read the target identity again. */
esp_err_t ms51_icp_reenter(ms51_icp_identity_t *identity);

/** Exit ICP mode, release nRESET, and return all three GPIOs to high impedance. */
void ms51_icp_close(void);

bool ms51_icp_is_open(void);

esp_err_t ms51_icp_read_flash(uint32_t address, void *data, size_t length);
esp_err_t ms51_icp_write_flash(uint32_t address, const void *data, size_t length);
esp_err_t ms51_icp_page_erase(uint32_t address);
esp_err_t ms51_icp_mass_erase(void);

/** Pulse the target reset pin and then release all pins. */
esp_err_t ms51_icp_reset_target(void);

#ifdef __cplusplus
}
#endif
