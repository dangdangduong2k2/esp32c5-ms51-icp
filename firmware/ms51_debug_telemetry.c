#include "ms51_debug_telemetry.h"

static ms51_debug_write_byte_fn s_writer;

static void write_byte(ms51_debug_u8_t byte)
{
    if (s_writer != 0) {
        s_writer(byte);
    }
}

static ms51_debug_u8_t name_char_is_safe(char value)
{
    return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') ||
           (value >= '0' && value <= '9') || value == '_' || value == '-' ||
           value == '.' || value == '[' || value == ']';
}

static void write_name(const char *name)
{
    if (name == 0) {
        return;
    }
    while (*name != '\0') {
        const char value = *name++;
        write_byte((ms51_debug_u8_t)(name_char_is_safe(value) ? value : '_'));
    }
}

static void write_log_text(const char *text)
{
    if (text == 0) {
        return;
    }
    while (*text != '\0') {
        const char value = *text++;
        write_byte((ms51_debug_u8_t)(value == '\r' || value == '\n' ? ' ' : value));
    }
}

static void write_unsigned(ms51_debug_u32_t value)
{
    char digits[10];
    ms51_debug_u8_t count = 0;
    do {
        digits[count++] = (char)('0' + value % 10u);
        value /= 10u;
    } while (value != 0u);
    while (count > 0u) {
        write_byte((ms51_debug_u8_t)digits[--count]);
    }
}

static void write_signed(ms51_debug_i32_t value)
{
    if (value < 0) {
        write_byte('-');
        /* Avoid overflowing when value is INT32_MIN. */
        write_unsigned((ms51_debug_u32_t)(-(value + 1)) + 1UL);
    } else {
        write_unsigned((ms51_debug_u32_t)value);
    }
}

static void write_variable_prefix(const char *name)
{
    write_log_text("@var,");
    write_name(name);
    write_byte(',');
}

static void finish_line(void)
{
    write_byte('\r');
    write_byte('\n');
}

void ms51_debug_set_writer(ms51_debug_write_byte_fn writer)
{
    s_writer = writer;
}

void ms51_debug_log(const char *text)
{
    write_log_text("@log,");
    write_log_text(text);
    finish_line();
}

void ms51_debug_var_bool(const char *name, ms51_debug_u8_t value)
{
    write_variable_prefix(name);
    write_log_text(value != 0 ? "true" : "false");
    finish_line();
}

void ms51_debug_var_u8(const char *name, ms51_debug_u8_t value)
{
    write_variable_prefix(name);
    write_unsigned(value);
    finish_line();
}

void ms51_debug_var_i8(const char *name, ms51_debug_i8_t value)
{
    write_variable_prefix(name);
    write_signed(value);
    finish_line();
}

void ms51_debug_var_u16(const char *name, ms51_debug_u16_t value)
{
    write_variable_prefix(name);
    write_unsigned(value);
    finish_line();
}

void ms51_debug_var_i16(const char *name, ms51_debug_i16_t value)
{
    write_variable_prefix(name);
    write_signed(value);
    finish_line();
}

void ms51_debug_var_u32(const char *name, ms51_debug_u32_t value)
{
    write_variable_prefix(name);
    write_unsigned(value);
    finish_line();
}

void ms51_debug_var_i32(const char *name, ms51_debug_i32_t value)
{
    write_variable_prefix(name);
    write_signed(value);
    finish_line();
}
