package com.aireview.user.service;

/**
 * 身份证号的校验与脱敏。
 *
 * <p>身份证号在本系统里是成员的唯一编码，导入时必须挡住手误——一个错号会造成一条永久
 * 错档，而且因为唯一约束还会挤掉真正该用这个号的人。所以除了长度和字符，这里还校验
 * GB 11643 的末位校验码，能拦下绝大多数录入错误。
 *
 * <p>同时它是个人敏感信息：对外展示一律走 {@link #mask}，完整号码只在服务端用于查重。
 */
public final class IdCardSupport {

    /** 加权因子与校验码表，见 GB 11643-1999 附录A。 */
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private IdCardSupport() {
    }

    /** 归一化：去空白、小写 x 统一成大写。 */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String value = raw.replaceAll("\\s", "").toUpperCase();
        return value.isEmpty() ? null : value;
    }

    /**
     * 校验 18 位二代身份证号。
     *
     * @return 校验失败的原因；通过时返回 null
     */
    public static String validate(String idCard) {
        if (idCard == null || idCard.isBlank()) return "身份证号不能为空";
        String value = normalize(idCard);
        if (value.length() != 18) return "身份证号必须为 18 位";
        for (int i = 0; i < 17; i++) {
            if (!Character.isDigit(value.charAt(i))) return "身份证号前 17 位必须是数字";
        }
        char last = value.charAt(17);
        if (!Character.isDigit(last) && last != 'X') return "身份证号末位只能是数字或 X";

        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (value.charAt(i) - '0') * WEIGHTS[i];
        }
        if (CHECK_CODES[sum % 11] != last) return "身份证号校验位不正确，请核对";
        return null;
    }

    /**
     * 脱敏：保留前 6 位（地区码）与后 4 位，中间掩去。
     *
     * <p>末 4 位保留是为了让管理员能在列表里区分同名成员；地区码本身不指向个人。
     */
    public static String mask(String idCard) {
        String value = normalize(idCard);
        if (value == null) return null;
        if (value.length() <= 10) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 6)
                + "*".repeat(value.length() - 10)
                + value.substring(value.length() - 4);
    }
}
