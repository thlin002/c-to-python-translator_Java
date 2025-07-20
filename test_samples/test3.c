#include <stdio.h>
#include <stdbool.h>

/*
 This is a block comment
 that spans multiple lines.
*/

// A subfunction to calculate factorial recursively.
int factorial(int n) {
    // Base case for the recursion
    if (n == 0) {
        return 1;
    } else {
        // Recursive step
        return n * factorial(n - 1);
    }
}

int main() {
    int values[] = {5, 8, 3, 10, 2}; // An array of integers
    int count = sizeof(values) / sizeof(values[0]); // Calculate array length

    printf("Processing numbers...\n");

    // Loop through the array
    for (int i = 0; i < count; i++) {
        int current_value = values[i];

        // A nested conditional statement
        if (current_value < 5) {
            printf("%d is small. Factorial: %d\n", current_value, factorial(current_value));
        } else if (current_value > 5) {
            printf("%d is large.\n", current_value);
        } else {
            printf("%d is exactly five!\n", current_value);
        }
    }

    // Another standalone comment
    printf("...done.\n");

    return 0; // Indicate successful execution
}