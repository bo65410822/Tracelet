package com.lzb.core

data class StoragePolicy(
    val maxFileCount: Int = 30,
    val maxTotalBytes: Long = 10 * 1024 * 1024,
    val maxFileAge: Long = 7 * 24 * 60 * 60 * 1000L
)
