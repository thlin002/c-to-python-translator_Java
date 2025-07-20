#include <stdio.h>
#include <stdbool.h>

// Subfunction to check if a number is even
bool is_even(int num) {
    return num % 2 == 0;
}

int main() {
    int numbers[] = {1, 2, 3, 4, 5, 6};
    int length = sizeof(numbers) / sizeof(numbers[0]);

    // Loop through array and call the subfunction
    for (int i = 0; i < length; i++) {
        if (is_even(numbers[i])) {
            printf("%d is even\n", numbers[i]);
        }
    }

    return 0;
}
