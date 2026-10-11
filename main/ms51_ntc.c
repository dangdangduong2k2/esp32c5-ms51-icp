#include "ms51_ntc.h"
#include <math.h>

const char *ms51_ntc_temperature(int millivolts, int raw, int *centi_celsius)
{
    *centi_celsius = 0; // valid only when return value is "ok"
    if (millivolts < 0 || raw < 0 || raw > 4095) return "invalid";
    if (millivolts <= 10) return "short";
    if (millivolts >= 3290 || raw >= 4090) return "open";
    const double resistance = 10000.0 * millivolts / (3300.0 - millivolts);
    const double kelvin = 1.0 / (1.0 / 298.15 + log(resistance / 10000.0) / 3950.0);
    const double celsius = kelvin - 273.15;
    if (!isfinite(celsius) || celsius < -55.0 || celsius > 125.0) return "out_of_range";
    *centi_celsius = (int)lround(celsius * 100.0);
    return "ok";
}
