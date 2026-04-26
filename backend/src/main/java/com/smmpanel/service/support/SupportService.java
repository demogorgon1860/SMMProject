package com.smmpanel.service.support;

import com.smmpanel.dto.support.AddMessageRequest;
import com.smmpanel.dto.support.CreateTicketRequest;
import com.smmpanel.dto.support.TicketMessageResponse;
import com.smmpanel.dto.support.TicketResponse;
import com.smmpanel.entity.SupportTicket;
import com.smmpanel.entity.SupportTicketMessage;
import com.smmpanel.entity.User;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.jpa.SupportTicketMessageRepository;
import com.smmpanel.repository.jpa.SupportTicketRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Support tickets — minimal threaded ticket system. Phase 3 ships:
 *
 * <ul>
 *   <li>List my tickets / read one
 *   <li>Open a new ticket (first message is the description)
 *   <li>Add a follow-up message to an existing ticket
 * </ul>
 *
 * Admin views and replies live in a separate operator endpoint added later.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportService {

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketMessageRepository messageRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<TicketResponse> listMyTickets() {
        User user = currentUser();
        return ticketRepository.findByUserIdOrderByUpdatedAtDesc(user.getId()).stream()
                .map(TicketResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMyTicket(Long ticketId) {
        User user = currentUser();
        SupportTicket ticket =
                ticketRepository
                        .findByIdAndUserId(ticketId, user.getId())
                        .orElseThrow(() -> new AccessDeniedException("Ticket not visible"));
        List<TicketMessageResponse> messages =
                messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId()).stream()
                        .map(TicketMessageResponse::from)
                        .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket", TicketResponse.from(ticket));
        body.put("messages", messages);
        return body;
    }

    @Transactional
    public TicketResponse createTicket(CreateTicketRequest request) {
        User user = currentUser();

        SupportTicket ticket =
                SupportTicket.builder()
                        .userId(user.getId())
                        .topic(request.getTopic().trim())
                        .subject(request.getSubject().trim())
                        .status(SupportTicket.Status.OPEN)
                        .orderId(request.getOrderId())
                        .lastUserMessageAt(LocalDateTime.now())
                        .build();
        ticket = ticketRepository.save(ticket);

        SupportTicketMessage first =
                SupportTicketMessage.builder()
                        .ticketId(ticket.getId())
                        .authorKind(SupportTicketMessage.AuthorKind.USER)
                        .authorUserId(user.getId())
                        .body(request.getDescription())
                        .build();
        messageRepository.save(first);

        log.info("User {} opened ticket {}", user.getId(), ticket.getId());
        return TicketResponse.from(ticket);
    }

    @Transactional
    public TicketMessageResponse addMessage(Long ticketId, AddMessageRequest request) {
        User user = currentUser();
        SupportTicket ticket =
                ticketRepository
                        .findByIdAndUserId(ticketId, user.getId())
                        .orElseThrow(() -> new AccessDeniedException("Ticket not visible"));

        if (ticket.getStatus() == SupportTicket.Status.CLOSED) {
            throw new IllegalStateException("Ticket is closed — open a new one to follow up.");
        }

        SupportTicketMessage message =
                SupportTicketMessage.builder()
                        .ticketId(ticket.getId())
                        .authorKind(SupportTicketMessage.AuthorKind.USER)
                        .authorUserId(user.getId())
                        .body(request.getBody())
                        .build();
        message = messageRepository.save(message);

        ticket.setLastUserMessageAt(LocalDateTime.now());
        if (ticket.getStatus() == SupportTicket.Status.WAITING) {
            ticket.setStatus(SupportTicket.Status.OPEN);
        }
        ticketRepository.save(ticket);

        return TicketMessageResponse.from(message);
    }

    // ---------------------------------------------------------------------
    // Admin / operator side. Calling code must enforce role checks via
    // Spring Security at the controller level — this service trusts that.
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<TicketResponse> adminListTickets(SupportTicket.Status status, Pageable pageable) {
        return ticketRepository.searchAll(status, pageable).map(TicketResponse::from);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> adminGetTicket(Long ticketId) {
        SupportTicket ticket =
                ticketRepository
                        .findById(ticketId)
                        .orElseThrow(() -> new IllegalArgumentException("Ticket not found"));
        List<TicketMessageResponse> messages =
                messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId()).stream()
                        .map(TicketMessageResponse::from)
                        .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket", TicketResponse.from(ticket));
        body.put("messages", messages);
        return body;
    }

    /**
     * Admin posts a reply on a ticket. Bumps {@code lastAdminMessageAt} so the user's panel can
     * show an "unread" badge, and flips the ticket from {@code OPEN} to {@code WAITING} (waiting on
     * the user). Closed tickets reject the reply — admins close-then-reopen by creating a follow-up
     * ticket, same as users.
     */
    @Transactional
    public TicketMessageResponse adminAddMessage(Long ticketId, AddMessageRequest request) {
        User admin = currentUser();
        SupportTicket ticket =
                ticketRepository
                        .findById(ticketId)
                        .orElseThrow(() -> new IllegalArgumentException("Ticket not found"));

        if (ticket.getStatus() == SupportTicket.Status.CLOSED) {
            throw new IllegalStateException("Ticket is closed — reopen before replying.");
        }

        SupportTicketMessage message =
                SupportTicketMessage.builder()
                        .ticketId(ticket.getId())
                        .authorKind(SupportTicketMessage.AuthorKind.ADMIN)
                        .authorUserId(admin.getId())
                        .body(request.getBody())
                        .build();
        message = messageRepository.save(message);

        ticket.setLastAdminMessageAt(LocalDateTime.now());
        if (ticket.getStatus() == SupportTicket.Status.OPEN) {
            ticket.setStatus(SupportTicket.Status.WAITING);
        }
        ticketRepository.save(ticket);

        log.info("Admin {} replied on ticket {}", admin.getId(), ticket.getId());
        return TicketMessageResponse.from(message);
    }

    /**
     * Update ticket status. Only OPEN/WAITING/CLOSED are valid. Used by admins to close resolved
     * tickets or reopen a closed one for follow-up.
     */
    @Transactional
    public TicketResponse adminSetStatus(Long ticketId, SupportTicket.Status status) {
        if (status == null) throw new IllegalArgumentException("Status is required");
        SupportTicket ticket =
                ticketRepository
                        .findById(ticketId)
                        .orElseThrow(() -> new IllegalArgumentException("Ticket not found"));
        ticket.setStatus(status);
        ticketRepository.save(ticket);
        return TicketResponse.from(ticket);
    }

    private User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new UserNotFoundException("Not authenticated");
        }
        return userRepository
                .findByUsername(auth.getName())
                .orElseThrow(() -> new UserNotFoundException("User not found"));
    }
}
