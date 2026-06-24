package be.vdab.logguard.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-21: strip KBO / email / LDAP-DN; keep UUIDs and numeric ids. */
class TenantDataSanitizerTest {

	private final TenantDataSanitizer sanitizer = new TenantDataSanitizer();

	@Test
	void stripsKboNumber() {
		assertEquals("company [KBO] failed", sanitizer.sanitize("company 0123.456.789 failed"));
	}

	@Test
	void stripsEmail() {
		assertEquals("user [EMAIL] not found", sanitizer.sanitize("user jan.peeters@vdab.be not found"));
	}

	@Test
	void stripsLdapDnFragment() {
		String out = sanitizer.sanitize("triggered by cn=MASTERBDB,ou=users,ou=intern,O=VDAB here");
		assertEquals("triggered by [LDAP-DN] here", out);
	}

	@Test
	void keepsUuidAndNumericIdsAndPorts() {
		String input = "id 12345 uuid 550e8400-e29b-41d4-a716-446655440000 on port 9200";
		assertEquals(input, sanitizer.sanitize(input));
	}

	@Test
	void nullStaysNull() {
		assertEquals(null, sanitizer.sanitize(null));
	}

	@Test
	void stripsMultipleInOneString() {
		String out = sanitizer.sanitize("0123.456.789 mailto a@b.co cn=X,ou=Y");
		assertTrue(out.contains("[KBO]"));
		assertTrue(out.contains("[EMAIL]"));
		assertTrue(out.contains("[LDAP-DN]"));
	}
}
