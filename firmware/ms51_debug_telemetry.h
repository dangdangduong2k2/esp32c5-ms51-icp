#ifndef MS51_DEBUG_TELEMETRY_H
#define MS51_DEBUG_TELEMETRY_H

/*
 * Small, toolchain-neutral runtime telemetry helper for MS51 firmware.
 *
 * The application must configure UART1 TX on P1.6 / ICE_DAT first, then pass
 * a byte writer to ms51_debug_set_writer(). These helpers emit one ASCII line
 * at a time, for example:
 *
 *   @var,adc_raw,812
 *   @var,state,3
 *   @log,boot complete
 *
 * ESP32-S3 receives the lines on GPIO6 and shows them in its web interface.
 */

/*
 * Do not rely on optional standard integer/Boolean headers. Older 8051
 * toolchains, including Keil C51, do not always ship them.
 */
typedef unsigned char ms51_debug_u8_t;
typedef signed char ms51_debug_i8_t;
typedef unsigned int ms51_debug_u16_t;
typedef signed int ms51_debug_i16_t;
typedef unsigned long ms51_debug_u32_t;
typedef signed long ms51_debug_i32_t;

typedef void (*ms51_debug_write_byte_fn)(ms51_debug_u8_t byte);

void ms51_debug_set_writer(ms51_debug_write_byte_fn writer);
void ms51_debug_log(const char *text);
void ms51_debug_var_bool(const char *name, ms51_debug_u8_t value);
void ms51_debug_var_u8(const char *name, ms51_debug_u8_t value);
void ms51_debug_var_i8(const char *name, ms51_debug_i8_t value);
void ms51_debug_var_u16(const char *name, ms51_debug_u16_t value);
void ms51_debug_var_i16(const char *name, ms51_debug_i16_t value);
void ms51_debug_var_u32(const char *name, ms51_debug_u32_t value);
void ms51_debug_var_i32(const char *name, ms51_debug_i32_t value);

#endif
