# MOS 6502

This is my own playground with a huge basic implementation of simulating a processor chip MOS 6502.

Here I'm just playing with how the functions must work and also how to organize the instructions.

At this point I just implemented the instructions `ADC`, `LDA` and `AND`.

I also implemented the addressing modes retrieving in separate logic of the instructions implementations itself. Im doing it by mapping addressing modes in a flat `mutableMapOf<Int, () -> Int>()`, where I can identify a function of address mode with a easy integer number.

All the code is in a single-file [Main.kt](src%2Fmain%2Fkotlin%2FMain.kt).