package com.example.exception;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.example.common.Result;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

@ControllerAdvice("com.example.controller")
public class GlobalExceptionHandler {

    private static final Log log = LogFactory.get();

    @ExceptionHandler(Exception.class)
    // 普通异常返回统一 JSON 结构
    @ResponseBody
    public Result error(Exception e) {
        log.error("异常信息：", e);
        return Result.error();
    }

    @ExceptionHandler(CustomException.class)
    // 业务异常返回统一 JSON 结构，并保留业务错误码和提示
    @ResponseBody
    public Result error(CustomException e) {
        return Result.error(e.getCode(), e.getMsg());
    }
}
