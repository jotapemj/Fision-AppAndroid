package com.jotapeveloper.fision.cloud;

/** Body for POST /auth/change-password. */
public class ChangePasswordRequest {
    public String old_password;
    public String new_password;

    public ChangePasswordRequest(String oldPassword, String newPassword) {
        this.old_password = oldPassword;
        this.new_password = newPassword;
    }
}
