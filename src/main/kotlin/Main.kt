@file:Suppress("unused", "ArrayInDataClass", "PropertyName", "PrivatePropertyName", "MemberVisibilityCanBePrivate")

import kotlin.system.measureNanoTime

const val ONE_KILOBYTE = 1024

/**
 * Maximum frequency rate of the clocks that MOS6502 can do.§
 *
 * In the internet we have that this rate is 1..3MHz, then I set 3MHz 😳
 *
 * This value is used to measure execution time of our Kotlin functions and see if
 * then executed faster than MOS6502 could do.
 *
 * Normally we expect our code runs faster because we have more powerful computers
 * in nowadays. Due to this, we wait some fraction of time to make our functions
 * more "slow", in the most precise approximation of original duration.
 *
 * The original execution instruction time duration is measured by dividing
 * their actual number of cycles needed (described in the specification) by this
 * frequency value.
 *
 * Wwe try to measure our kotlin functions speed using nano-time.
 */
const val CLOCKS_PER_SECOND = 3_000_000f // 3MHz -> cycles/clocks per second

// Listing addressing modes based on order of original resource
const val MODE_ACCUMULATOR = 0x0
const val MODE_IMMEDIATE = 0x1
const val MODE_ABSOLUTE = 0x2
const val MODE_ZERO_PAGE = 0x3
const val MODE_ZERO_PAGE_INDEXED_X = 0x4
const val MODE_ZERO_PAGE_INDEXED_Y = 0x5
const val MODE_INDEXED_ABSOLUTE_X = 0x6
const val MODE_INDEXED_ABSOLUTE_Y = 0x7
const val MODE_IMPLIED = 0x8
const val MODE_RELATIVE = 0x9
const val MODE_INDIRECT_X = 0xA
const val MODE_INDIRECT_Y = 0xB
const val MODE_ABSOLUTE_INDIRECT = 0xC

/**
 * This is a memory structure created in order to the processor
 * work on it.
 *
 * Imagine the processor as a real world worker. This real world worker
 * can only do its job if he have a table to that. The memory is this
 * kind of table.
 *
 * Probably we need to create a memory map interface to define how to slice
 * and organize this amount of memory
 */
data class Memory(private val content: IntArray = IntArray(64 * ONE_KILOBYTE) { 0x00 }) {

  fun size() = content.size

  fun clear() {
    content.fill(0x00)
  }

  /**
   * we receive addresses in "Int" type because we have a long range of indexes.
   * then a single byte is not enough to cover all of this range, a two-byte word
   * is needed. Typically a "Short" type should be the right type, but "Int" is coolest.
   */
  private fun read(address: Int): Int {
    if (address !in content.indices) {
      throw IndexOutOfBoundsException("The address [$address] is out of memory size (${content.size})")
    }

    return content[address]
  }

  private fun write(address: Int, value: Int) {
    if (address !in content.indices) {
      throw IndexOutOfBoundsException("The address [$address] is out of memory size (${content.size})")
    }

    content[address] = value
  }

  operator fun get(address: Int) = read(address)

  operator fun set(address: Int, value: Int) = write(address, value)
}

/**
 * Our actual representation of the MOS6502 processor chip.
 *
 * This entity just holds the general registers and the status flags.
 *
 * The status flags are treated as individual variables. In the future we can
 * treat then as a single 8-bit/byte, if needed.
 *
 * This entity can also execute instructions contained in the passed [memory],
 * through the function [executeNext].
 *
 * The if we have a binary program properly loaded in that memory, we can start
 * executing it using this class.
 *
 * The start of execution is based on the [PC] ("program count") variable,
 * which represents the actual position on memory that this entity will look for
 * an instruction "opcode".
 *
 * An "opcode" is basically a number (hexadecimal number) which is used to
 * identify an instruction itself. In this simulator, the instructions are just
 * Kotlin functions that are called based on that opcode checking.
 *
 * Instructions also exists in different addressing modes. In terms of assembly
 * programming each instruction has only a single identifier, that we can refer
 * as their "mnemonic label". E.g.: exists a instruction to sum a number with
 * some previously stored number called "add with carry". In assembly mnemonics
 * this instruction is called "ADC", however, this instruction can be executed
 * in a lot of different addressing modes, for example, "ADC" can be in
 * "immediate mode" or "absolute mode". Each variant is designed by a different
 * opcode. In this example we have "0x69" and "0x6D", respectively. This means
 * that either "0x69" either "0x6D" points to the "same" instruction, that is
 * the "ADC" one.
 *
 * Addressing modes are just different ways of retrieving the value that the
 * instruction will use in its calculation. For example, we can obtain this
 * "operand" value just next of the instruction opcode or in other regions
 * of the memory. Each mode describes its rules of how to obtain the operand
 * number.
 */
data class MOS6502(val memory: Memory) {

  // general registers; all are treated as 8-bit length
  private var A: Int = 0
  private var X: Int = 0
  private var Y: Int = 0
  private var SP: Int = 0xFF // always points to top of the stack
  private var PC: Int = 0

  // status flags
  private var C: Int = 0
  private var Z: Int = 0
  private var I: Int = 0
  private var D: Int = 0
  private var B: Int = 0
  private var V: Int = 0
  private var N: Int = 0

  /**
   * Maps custom local "opcodes" to custom local functions.
   *
   * These functions just retrieves operands based on the rules
   * of each addressing mode.
   *
   * Each addressing mode is labeled by a simple hex number and
   * each function should return a single 8-bit byte, which is
   * the actual operand.
   *
   * This helps to separate and avoid repeating logic of retrieving
   * operands.
   *
   * We fill this array with functions. Each element can be literally invoked
   * like "`addressingModes[idx]()`", which should return the appropriate
   * operand based on the accessed function.
   *
   * TODO: implement cross-page checking to increment 1 extra cycle when it happen 😪
   */
  val addressingModes = Array(0xC + 1) { { 0x00 } }

  init {
    addressingModes[MODE_ACCUMULATOR] = { A }

    addressingModes[MODE_IMMEDIATE] = { memory[PC + 1] }

    addressingModes[MODE_ZERO_PAGE] = {
      val instructionArgument = memory[PC + 1]
      memory[instructionArgument]
    }

    addressingModes[MODE_ZERO_PAGE_INDEXED_X] = {
      val zeroPageAddress = memory[PC + 1]
      val effectiveAddress = (zeroPageAddress + X) and 0xFF
      memory[effectiveAddress]
    }

    addressingModes[MODE_ZERO_PAGE_INDEXED_Y] = {
      val zeroPageAddress = memory[PC + 1]
      val effectiveAddress = (zeroPageAddress + Y) and 0xFF
      memory[effectiveAddress]
    }

    addressingModes[MODE_ABSOLUTE] = {
      val lo = memory[PC + 1]
      val hi = memory[PC + 2]
      val address = (hi shl 8) or lo
      memory[address]
    }

    addressingModes[MODE_INDEXED_ABSOLUTE_X] = {
      val lo = memory[PC + 1]
      val hi = memory[PC + 2]
      val baseAddress = (hi shl 8) or lo
      val effectiveAddress = baseAddress + X
      memory[effectiveAddress]
    }

    addressingModes[MODE_INDEXED_ABSOLUTE_Y] = {
      val lo = memory[PC + 1]
      val hi = memory[PC + 2]
      val baseAddress = (hi shl 8) or lo
      val effectiveAddress = baseAddress + Y
      memory[effectiveAddress]
    }

    addressingModes[MODE_RELATIVE] = {
      val offset = memory[PC + 1]
      val relativeAddress =
        if (offset < 0x80)
          PC + 2 + (offset)
        else
          PC + 2 + offset - 0x100
      memory[relativeAddress]
    }

    addressingModes[MODE_INDIRECT_X] = {
      val baseAddress = (memory[PC + 1] + X) and 0xFF
      val lo = memory[baseAddress]
      val hi = memory[(baseAddress + 1) and 0xFF]
      val effectiveAddress = (hi shl 8) or lo
      memory[effectiveAddress]
    }

    // TODO: check if is right
    addressingModes[MODE_INDIRECT_Y] = {
      val baseAddress = memory[PC + 1]
      val lo = memory[baseAddress]
      val hi = memory[(baseAddress + 1) and 0xFF]
      val baseEffectiveAddress = (hi shl 8) or lo
      val effectiveAddress = baseEffectiveAddress + Y
      memory[effectiveAddress]
    }

    // TODO: check if is right
    addressingModes[MODE_ABSOLUTE_INDIRECT] = {
      val lo = memory[PC + 1]
      val hi = memory[PC + 2]
      val pointerAddress = (hi shl 8) or lo
      val lowTarget = memory[pointerAddress]
      val highTarget = memory[(pointerAddress + 1) and 0xFFFF]
      val effectiveAddress = (highTarget shl 8) or lowTarget
      memory[effectiveAddress]
    }

    addressingModes[MODE_IMPLIED] = { 0x00 }

    this.reset()
  }

  fun reset() {
    PC = 0xFFFC
    SP = 0xFD
    C = 0; Z = 0; I = 0; D = 0; B = 0; V = 0; N = 0
  }

  // just executes current PC (program count) instruction that is inside the memory
  fun executeNext() {
    var currentNumCycles: Int
    val elapsedNs = measureNanoTime {
      currentNumCycles = when (val nextOpCode = memory[PC]) {
        // ADC instructions and its modes
        0x69 -> adc(MODE_IMMEDIATE, 2, 2)
        0x65 -> adc(MODE_ZERO_PAGE, 2, 3)
        0x75 -> adc(MODE_ZERO_PAGE_INDEXED_X, 2, 4)
        0x6D -> adc(MODE_ABSOLUTE, 3, 4)
        0x7D -> adc(MODE_INDEXED_ABSOLUTE_X, 3, 4)
        0x79 -> adc(MODE_INDEXED_ABSOLUTE_Y, 3, 4)
        0x61 -> adc(MODE_INDIRECT_X, 2, 6)
        0x71 -> adc(MODE_INDIRECT_Y, 2, 5)

        // LDA instruction and its modes
        0xA9 -> lda(MODE_IMMEDIATE, 2, 2)
        0xA5 -> lda(MODE_ZERO_PAGE, 2, 3)
        0xB5 -> lda(MODE_ZERO_PAGE_INDEXED_X, 2, 4)
        0xAD -> lda(MODE_ABSOLUTE, 3, 4)
        0xBD -> lda(MODE_INDEXED_ABSOLUTE_X, 3, 4)
        0xB9 -> lda(MODE_INDEXED_ABSOLUTE_Y, 3, 4)
        0xA1 -> lda(MODE_INDIRECT_X, 2, 6)
        0xB1 -> lda(MODE_INDIRECT_Y, 2, 5)

        // AND instruction and its modes
        0x29 -> and(MODE_IMMEDIATE, 2, 2)
        0x25 -> and(MODE_ZERO_PAGE, 2, 3)
        0x35 -> and(MODE_ZERO_PAGE_INDEXED_X, 2, 4)
        0x2D -> and(MODE_ABSOLUTE, 3, 4)
        0x3D -> and(MODE_INDEXED_ABSOLUTE_X, 3, 4)
        0x39 -> and(MODE_INDEXED_ABSOLUTE_Y, 3, 4)
        0x21 -> and(MODE_INDIRECT_X, 2, 6)
        0x31 -> and(MODE_INDIRECT_Y, 2, 5)

        // ASL instruction and its modes
        0x0A -> asl(MODE_ACCUMULATOR, 1, 2)
        0x06 -> asl(MODE_ZERO_PAGE, 2, 5)
        0x16 -> asl(MODE_ZERO_PAGE_INDEXED_X, 2, 6)
        0x0E -> asl(MODE_ABSOLUTE, 3, 6)
        0x1E -> asl(MODE_INDEXED_ABSOLUTE_X, 3, 7)

        else -> throw IllegalStateException("Unsupported opcode detected in program count position [$PC]: [$nextOpCode]")
      }
    }

    // we convert expected time to nanoseconds (my best trying to measure fast timing)
    val expectedNs = ((currentNumCycles / CLOCKS_PER_SECOND) * 1_000_000_000).toLong()

    // we sleep our code if above function was too fast than expected
    if (elapsedNs < expectedNs) basicSleep(expectedNs - elapsedNs)
  }

  /**
   * "ADC" instruction; "Add with Carry".
   *
   * This instruction takes the effective operand and sums with the current
   * [A] register and also with the current [C] register.
   *
   * After, the instruction updates the flags [N], [Z], [C] and [V] based on
   * the obtained result.
   */
  fun adc(addressingMode: Int, nBytes: Int, nCycles: Int): Int {
    val operand = addressingModes[addressingMode]()
    val a = A
    val c = C

    // performing the formula
    A = (a + operand + c)

    // updates the needed flags
    C = if (A > 0xFF) 1 else 0
    Z = if (A == 0) 1 else 0
    N = if (A and 0x80 != 0) 1 else 0

    // TODO: found on internet, should be checked
    V = if (((a xor operand) and 0x80) == 0 && ((a xor A) and 0x80) != 0) 1 else 0

    // advances the counter based on size of this instruction
    PC += nBytes
    return nCycles
  }

  /**
   * "AND" instruction; "Logical AND between operand and [A] register"
   *
   * This instruction will perform the logical AND (&) operation
   * between the retrieved operand with the current value of the [A]
   * register.
   *
   * The result will be stored in the [A] register itself.
   *
   * In the end, the instruction updates the flags [N] and [Z]
   * based on the result.
   */
  fun and(addressingMode: Int, nBytes: Int, nCycles: Int): Int {
    val operand = addressingModes[addressingMode]()

    A = A and operand

    N = if (A and 0x80 != 0) 1 else 0
    Z = if (A == 0) 1 else 0

    PC += nBytes
    return nCycles
  }

  /**
   * "LDA" instruction; "Load [A] register with operand"
   *
   * This instruction just replaces the value of the [A] register
   * with the retrieved operand.
   *
   * After the process, the instruction updates the flags [N] and
   * [Z] based on operand.
   */
  fun lda(addressingMode: Int, nBytes: Int, nCycles: Int): Int {
    val operand = addressingModes[addressingMode]()

    A = operand and 0xFF

    N = if (A and 0x80 != 0) 1 else 0
    Z = if (A == 0) 1 else 0

    PC += nBytes
    return nCycles
  }

  /**
   * "ASL" instruction; "Shift left operand or [A] register
   *
   * This instruction simply performs a left shit in the retrieved
   * operand. However, if we are facing the [MODE_ACCUMULATOR], then
   * the final result is stored in the [A] register itself, otherwise
   * the final result is stored in the same operand. This happens because
   * if the mode is not [MODE_ACCUMULATOR] then we are basically searching
   * the operand in somewhere of the memory, then this instruction will
   * replace the value at that address (which the operand will effective
   * be) with the shifted result.
   *
   * This instruction also updates the [C] flag with the 7th bit of the
   * retrieved operand. Beyond this, this instruction updates the flags
   * [N] and [Z] based on final result.
   */
  fun asl(addressingMode: Int, nBytes: Int, nCycles: Int): Int {
    val operand = addressingModes[addressingMode]()
    val nextCarry = (operand and 0x80) shr 7
    val result = (operand shl 1) and 0xFF

    if (addressingMode == MODE_ACCUMULATOR) {
      A = result
    } else {
      memory[operand] = result
    }

    // updates flags
    C = if (nextCarry != 0) 1 else 0
    Z = if (result == 0) 1 else 0
    N = if (result and 0x80 != 0) 1 else 0

    PC += nBytes
    return nCycles
  }

  private fun basicSleep(nanos: Long) {
    var elapsed: Long
    val startTime = System.nanoTime()
    do {
      elapsed = System.nanoTime() - startTime
    } while (elapsed < nanos)
  }
}