package com.bliss.survey.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SurveyDatabaseUrlTest {
    @Test
    fun `converts postgres scheme to jdbc postgresql`() {
        assertThat(SurveyDatabase.toJdbcUrl("postgres://user:pw@host:5432/db"))
            .isEqualTo("jdbc:postgresql://host:5432/db")
    }

    @Test
    fun `converts postgresql scheme to jdbc postgresql`() {
        assertThat(SurveyDatabase.toJdbcUrl("postgresql://user:pw@host:5432/db"))
            .isEqualTo("jdbc:postgresql://host:5432/db")
    }

    @Test
    fun `preserves query string`() {
        assertThat(SurveyDatabase.toJdbcUrl("postgres://user:pw@host:5432/db?sslmode=require"))
            .isEqualTo("jdbc:postgresql://host:5432/db?sslmode=require")
    }

    @Test
    fun `wraps bare IPv6 host in brackets`() {
        assertThat(SurveyDatabase.toJdbcUrl("postgres://user:pw@[::1]:5432/db"))
            .isEqualTo("jdbc:postgresql://[::1]:5432/db")
    }

    @Test
    fun `defaults to port 5432 when port is absent`() {
        assertThat(SurveyDatabase.toJdbcUrl("postgres://user:pw@host/db"))
            .isEqualTo("jdbc:postgresql://host:5432/db")
    }

    @Test
    fun `passes jdbc URLs through unchanged`() {
        val jdbcUrl = "jdbc:postgresql://host:5432/db"
        assertThat(SurveyDatabase.toJdbcUrl(jdbcUrl)).isEqualTo(jdbcUrl)
    }

    @Test
    fun `rejects unsupported scheme`() {
        assertThrows<IllegalArgumentException> {
            SurveyDatabase.toJdbcUrl("mysql://host:3306/db")
        }
    }
}
