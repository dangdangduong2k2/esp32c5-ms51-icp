#ifndef __STDBOOL_H__
#define __STDBOOL_H__

#if defined(TRUE)
#undef TRUE
#endif

#if defined(FALSE)
#undef FALSE
#endif

typedef BIT bool;
#define true 1
#define false 0
#define __bool_true_false_are_defined 1

#endif
