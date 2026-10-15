package com.oemtools.poldock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HolidayStoreTest {

    /** 6글자를 넘는 공휴일 이름은 날짜 뷰에 맞게 축약된다(2021~2028 데이터 기준). */
    @Test
    public void shortensLongHolidayNames() {
        assertEquals("대체공휴일", HolidayStore.shortName("대체공휴일(삼일절)"));
        assertEquals("대체공휴일", HolidayStore.shortName("대체공휴일(부처님오신날)"));
        assertEquals("임시공휴일", HolidayStore.shortName("임시공휴일(제21대 대통령 선거)"));
        assertEquals("크리스마스", HolidayStore.shortName("기독탄신일"));
        assertEquals("지방선거", HolidayStore.shortName("전국동시지방선거"));
        assertEquals("국회의원선거", HolidayStore.shortName("국회의원선거일"));
    }

    /** 6글자 이하 이름은 그대로 둔다. */
    @Test
    public void keepsShortNames() {
        for (String name : new String[]{"설날", "추석", "삼일절", "어린이날",
                "부처님오신날", "대통령선거일", "국회의원선거"}) {
            assertEquals(name, HolidayStore.shortName(name));
            assertTrue(name + " 는 6글자 이하", HolidayStore.shortName(name).length() <= 6);
        }
    }
}
