int fibonacci(int n) {
    if (n <= 1) { // Base case comment
        return n;
    }

    int a = 0;
    int b = 1;

    /* Loop to calculate fibonacci */
    for (int i = 2; i <= n; i += 1) {
        int temp = a + b;
        a = b;
        b = temp;
    }

    return b;
}

int main() {
    fibonacci(10);
    return 0;
}