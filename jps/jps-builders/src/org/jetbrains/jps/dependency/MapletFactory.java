// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jps.dependency;

public interface MapletFactory {
  <K extends SerializableGraphElement, V extends SerializableGraphElement> MultiMaplet<K, V> createSetMultiMaplet(String storageName);
}
