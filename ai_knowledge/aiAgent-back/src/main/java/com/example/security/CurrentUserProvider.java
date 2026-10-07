package com.example.security;

import com.example.entity.User;
import com.example.utils.TokenUtils;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {
    public User current() {
        return TokenUtils.getCurrentUser();
    }
}
