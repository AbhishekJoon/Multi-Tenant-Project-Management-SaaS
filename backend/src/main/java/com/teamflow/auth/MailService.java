package com.teamflow.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {
  private final JavaMailSender sender;
  private final String origin, from;

  public MailService(
      JavaMailSender sender,
      @Value("${teamflow.origin}") String origin,
      @Value("${teamflow.mail-from}") String from) {
    this.sender = sender;
    this.origin = origin;
    this.from = from;
  }

  public void sendLink(String recipient, String subject, String path, String token) {
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(recipient);
    message.setSubject(subject);
    message.setText(
        subject
            + "\n\n"
            + origin
            + path
            + "?token="
            + token
            + "\n\nIf you did not request this, ignore this email.");
    sender.send(
        message); // Delivery failures fail the transaction; development uses Mailpit, never a fake
    // success.
  }
}
