package main

import "fmt"

func test() {
	fmt.Println(2 ^ 32) // should be 1 << 32
}
