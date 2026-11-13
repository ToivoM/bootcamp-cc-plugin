package com.bootcampcc;

import java.time.Instant;
import lombok.Value;

/** A clan event (Discord scheduled event) as returned by GET /api/events. */
@Value
class ClanEvent
{
	String id;
	String name;
	String description;
	/** May be null. */
	String location;
	Instant start;
	Instant end;
	/** Discord status: scheduled, active, completed or canceled. */
	String status;
}
