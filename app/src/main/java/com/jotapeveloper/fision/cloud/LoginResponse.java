package com.jotapeveloper.fision.cloud;

/** Response body returned by POST /login and POST /auth/change-password. */
public class LoginResponse {
    public String token;
    public String user;
    /** Only set by /auth/change-password when the operation succeeded. */
    public Boolean ok;
}
