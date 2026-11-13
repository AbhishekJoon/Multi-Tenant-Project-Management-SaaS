package com.teamflow.security;

import java.util.UUID;

public record Actor(UUID id, String email, String name) {}
