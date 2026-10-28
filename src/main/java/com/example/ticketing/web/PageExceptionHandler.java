package com.example.ticketing.web;

import com.example.ticketing.web.controller.OpsPageController;
import com.example.ticketing.web.controller.PageController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;

// 화면 경로의 오류는 JSON 대신 화면으로 답한다. 맡을 화면 컨트롤러를 타입으로 정한다
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice(assignableTypes = {PageController.class, OpsPageController.class})
public class PageExceptionHandler {

    static final String VIEW = "booking/page-error";

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ModelAndView notFound(MethodArgumentTypeMismatchException e) {
        return page(HttpStatus.NOT_FOUND, "찾을 수 없는 화면이에요", "주소가 바뀌었거나 잘못 입력됐어요.");
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView failed(Exception e) {
        log.warn("[booking page] 화면을 그리지 못했다: {}", e.toString());
        return page(HttpStatus.INTERNAL_SERVER_ERROR, "화면을 열지 못했어요", "잠시 뒤 다시 열어 주세요.");
    }

    private static ModelAndView page(HttpStatus status, String title, String text) {
        ModelAndView view = new ModelAndView(VIEW);
        view.setStatus(status);
        view.addObject("errorTitle", title);
        view.addObject("errorText", text);
        return view;
    }
}
