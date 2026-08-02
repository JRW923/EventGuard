package com.eventguard.command.controller;

import com.eventguard.command.command.CreateOrderCommand;
import com.eventguard.command.handler.OrderCommandHandler;
import com.eventguard.common.dto.CommandResult;
import com.eventguard.gateway.service.PaymentCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderCommandControllerTest {

    @Mock OrderCommandHandler handler;
    @Mock PaymentCoordinator paymentCoordinator;
    OrderCommandController controller;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = new OrderCommandController(handler, paymentCoordinator);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(handler.handle(any(CreateOrderCommand.class))).thenReturn(CommandResult.success(1));
    }

    @Test
    void x_command_id_header_is_used_as_command_id() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/orders")
                        .header("X-Command-Id", id.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"totalAmount\":10}"))
                .andExpect(status().isOk());

        ArgumentCaptor<CreateOrderCommand> cap = ArgumentCaptor.forClass(CreateOrderCommand.class);
        verify(handler).handle(cap.capture());
        assertEquals(id, cap.getValue().getCommandId());
    }

    @Test
    void missing_x_command_id_falls_back_to_generated() throws Exception {
        mvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"totalAmount\":10}"))
                .andExpect(status().isOk());

        ArgumentCaptor<CreateOrderCommand> cap = ArgumentCaptor.forClass(CreateOrderCommand.class);
        verify(handler).handle(cap.capture());
        assertNotNull(cap.getValue().getCommandId());
    }

    @Test
    void invalid_x_command_id_falls_back_to_generated() throws Exception {
        mvc.perform(post("/orders")
                        .header("X-Command-Id", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"totalAmount\":10}"))
                .andExpect(status().isOk());

        ArgumentCaptor<CreateOrderCommand> cap = ArgumentCaptor.forClass(CreateOrderCommand.class);
        verify(handler).handle(cap.capture());
        assertNotNull(cap.getValue().getCommandId());
    }
}
