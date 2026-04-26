package com.smmpanel.service.email;

import java.util.Locale;

/**
 * Inline HTML templates for transactional email. Each method returns a self-contained HTML doc;
 * everything (typography, colors, spacing) is set with inline styles because real-world email
 * clients still strip {@code <style>}.
 *
 * <p>Templates intentionally avoid:
 *
 * <ul>
 *   <li>Web fonts (Outlook strips them) — system stack only
 *   <li>External images that could be blocked by image-loading defaults
 *   <li>JavaScript (filtered everywhere)
 * </ul>
 */
final class EmailTemplates {

    private EmailTemplates() {}

    private static final String FONT_STACK =
            "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
    private static final String MONO_STACK = "'JetBrains Mono',ui-monospace,SFMono-Regular,Menlo,monospace";

    public static String verificationCode(String username, String code) {
        String safeUsername = escape(username);
        String safeCode = escape(code);
        return wrap(
                "Verify your email",
                String.format(
                        Locale.ROOT,
                        "<p style='margin:0 0 16px;font-size:15px;line-height:1.55;color:#333'>"
                                + "Hi %s,</p>"
                                + "<p style='margin:0 0 24px;font-size:15px;line-height:1.55;color:#444'>"
                                + "Use this code to verify your email and activate your account."
                                + "</p>"
                                + "<div style='font-family:%s;font-size:34px;font-weight:700;"
                                + "letter-spacing:.18em;background:#f5f5f4;border:1px solid #e7e5e4;"
                                + "border-radius:10px;padding:18px;text-align:center;color:#0c0a09;"
                                + "margin:0 0 24px'>%s</div>"
                                + "<p style='margin:0 0 8px;font-size:13px;color:#78716c'>"
                                + "Expires in 24 hours.</p>"
                                + "<p style='margin:0;font-size:13px;color:#78716c'>"
                                + "If you didn't request this, you can ignore this email.</p>",
                        safeUsername, MONO_STACK, safeCode));
    }

    public static String passwordReset(String username, String resetUrl) {
        String safeUsername = escape(username);
        String safeUrl = escape(resetUrl);
        return wrap(
                "Reset your password",
                String.format(
                        Locale.ROOT,
                        "<p style='margin:0 0 16px;font-size:15px;line-height:1.55;color:#333'>"
                                + "Hi %s,</p>"
                                + "<p style='margin:0 0 20px;font-size:15px;line-height:1.55;color:#444'>"
                                + "Click the button below to choose a new password. The link expires"
                                + " in 1 hour.</p>"
                                + "<p style='margin:0 0 24px'>"
                                + "<a href='%s' style='display:inline-block;background:#4f46e5;"
                                + "color:#fff;text-decoration:none;padding:12px 24px;border-radius:8px;"
                                + "font-weight:600;font-size:14px'>Reset password</a></p>"
                                + "<p style='margin:0 0 8px;font-size:12px;color:#78716c;word-break:break-all'>"
                                + "Or paste this URL into your browser:<br/>%s</p>"
                                + "<p style='margin:24px 0 0;font-size:13px;color:#78716c'>"
                                + "If you didn't request a password reset, no action is needed —"
                                + " your password remains unchanged.</p>",
                        safeUsername, safeUrl, safeUrl));
    }

    public static String welcome(String username, String publicBaseUrl) {
        String safeUsername = escape(username);
        String safeUrl = escape(publicBaseUrl);
        return wrap(
                "Welcome to SMMWorld",
                String.format(
                        Locale.ROOT,
                        "<p style='margin:0 0 16px;font-size:15px;line-height:1.55;color:#333'>"
                                + "Welcome, %s.</p>"
                                + "<p style='margin:0 0 20px;font-size:15px;line-height:1.55;color:#444'>"
                                + "Your account is verified and ready. A few good first moves:</p>"
                                + "<ul style='margin:0 0 24px;padding-left:18px;font-size:14px;line-height:1.6;color:#444'>"
                                + "<li>Top up with crypto — USDT TRC-20 has low network fees.</li>"
                                + "<li>Browse the catalog at %s/services-list.</li>"
                                + "<li>Read the API docs if you're integrating: %s/api-docs.</li>"
                                + "</ul>"
                                + "<p style='margin:0 0 24px'>"
                                + "<a href='%s/dashboard' style='display:inline-block;background:#4f46e5;"
                                + "color:#fff;text-decoration:none;padding:12px 24px;border-radius:8px;"
                                + "font-weight:600;font-size:14px'>Go to dashboard</a></p>"
                                + "<p style='margin:0;font-size:13px;color:#78716c'>"
                                + "Questions? Reply to this email — average reply under 12 minutes during"
                                + " business hours.</p>",
                        safeUsername, safeUrl, safeUrl, safeUrl));
    }

    private static String wrap(String title, String body) {
        return String.format(
                Locale.ROOT,
                "<!doctype html><html lang='en'><head><meta charset='utf-8'/>"
                        + "<title>%s</title></head>"
                        + "<body style='margin:0;padding:0;background:#fafaf9;font-family:%s;color:#0c0a09'>"
                        + "<table role='presentation' width='100%%' cellpadding='0' cellspacing='0' style='background:#fafaf9;padding:32px 16px'>"
                        + "<tr><td align='center'>"
                        + "<table role='presentation' width='100%%' cellpadding='0' cellspacing='0' style='max-width:520px;background:#ffffff;border:1px solid #e7e5e4;border-radius:14px;overflow:hidden'>"
                        + "<tr><td style='padding:24px 28px 0'>"
                        + "<div style='font-family:%s;font-size:13px;font-weight:700;letter-spacing:.06em;color:#4f46e5;text-transform:uppercase'>SMMWorld</div>"
                        + "<h1 style='margin:8px 0 24px;font-size:22px;line-height:1.25;color:#0c0a09;letter-spacing:-0.01em'>%s</h1>"
                        + "</td></tr>"
                        + "<tr><td style='padding:0 28px 28px'>%s</td></tr>"
                        + "<tr><td style='padding:18px 28px;background:#f5f5f4;border-top:1px solid #e7e5e4;font-size:11px;color:#78716c'>"
                        + "Sent by SMMWorld · You're getting this because of an action on your account."
                        + "</td></tr>"
                        + "</table></td></tr></table></body></html>",
                escape(title), FONT_STACK, MONO_STACK, escape(title), body);
    }

    /** Minimal HTML escape — only for plain values we drop into the templates. */
    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#x27;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
