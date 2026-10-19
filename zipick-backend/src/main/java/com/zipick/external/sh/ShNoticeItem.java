package com.zipick.external.sh;

import java.time.LocalDate;

record ShNoticeItem(String sequence, String title, LocalDate registeredDate, String detailUrl) {
}
