// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsecureHashBuilderTest {
  @Test
  fun `empty map`() {
    val builder = InsecureHashBuilder()
    builder.putStringMap(mapOf())
    assertThat(builder.build()).isEqualTo(longArrayOf(8533677159333351289L, 8571778408965004314L))
  }

  @Test
  fun `string map with one item`() {
    val builder = InsecureHashBuilder()
    builder.putStringMap(hashMapOf("foo" to "bar"))
    assertThat(builder.build()).isEqualTo(longArrayOf(-6651769613387598404L, -116050364988532228L))
  }

  @Test
  fun `string map with several items`() {
    val builder = InsecureHashBuilder()
    builder.putStringMap(hashMapOf("foo" to "bar", "bar" to "foo", "oof" to "rab"))
    assertThat(builder.build()).isEqualTo(longArrayOf(639191527324638794L, 6156419864194977552L))
  }

  @Test
  fun `string int map`() {
    val builder = InsecureHashBuilder()
    builder.putStringIntMap(mapOf("foo" to 123, "bar" to 543))
    assertThat(builder.build()).isEqualTo(longArrayOf(5329019894502154008L, -5015802568783995987L))
  }

  @Test
  fun `several maps`() {
    val builder = InsecureHashBuilder()
    builder.putStringMap(hashMapOf("foo" to "bar"))
    builder.putStringIntMap(hashMapOf("foo" to 123, "bar" to 543))
    assertThat(builder.build()).isEqualTo(longArrayOf(8812214546876945120L, 9108116165422427982L))
  }
}