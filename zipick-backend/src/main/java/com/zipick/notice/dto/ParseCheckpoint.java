package com.zipick.notice.dto;

public record ParseCheckpoint(String documentKey, String parserVersion, long size, int unitCount) { }
