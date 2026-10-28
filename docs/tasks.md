# Tasks

Open **Tasks** in the bottom navigation. In the Dom workspace, choose a Sub profile to see its active, pending and scheduled tasks. Select a task card for instructions, attachments, completion requests and review. Back returns from a form to the task, then to its list. The Sub workspace opens its own Tasks list.

Open a Sub profile and choose **Tasks**. A Sub can also open Tasks from Home.

A Dom creates a task with a title, instructions, a start time, duration, and optionally one image, audio track or video. Select weekdays from Monday through Sunday for a recurring task; leave them unselected for a single task.

Recurring tasks keep their chosen local time and time zone when either device changes zones. A spring DST gap moves the start forward by the gap. An autumn overlap uses the earlier offset once. Duration is elapsed time from that resolved start. Each occurrence has a stable identity based on the task and its calendar date in the task's zone.

New tasks use the target Sub device timezone received over the authenticated Tasks connection. The timezone is read-only; sync with the Sub if it is unavailable or more than 30 minutes old. The Dom device timezone is never used for a remote Sub. Existing tasks retain their recorded timezone when either device travels or changes timezone, preserving deadlines, weekday repeats and occurrence identities. New tasks use the latest confirmed Sub timezone. Local tasks use this device timezone.

## Completion and refusal

During an active task, the Sub can request completion with text and up to five media attachments, or request review outside the app without proof. Both requests remain pending until the Dom explicitly accepts or denies them. A timely pending request does not expire while awaiting review. Denial ends that occurrence.

If the author enables refusal, the Sub can refuse an occurrence before its deadline. A recurring task also offers refusal of the entire series. A refusal does not count as failure or create a tribute. Refusing a series stops future occurrences and survives reconnects; previously submitted work still receives its explicit review.

## Failure tributes

Enable **Task failure** in the profile's Tributes settings and choose its amount, then enable **Failure tribute** for the task. A denied request or timeout can add one local tribute when protection is armed and the receiving device permits both Tasks and Tributes. Existing daily and weekly caps and the correction window apply. The task failure path does not schedule a payment.

An occurrence has a permanent failure receipt, including when eligibility or spending caps produce a zero amount. Replays, later settings changes and history trimming cannot assess it a second time. Permitted refusals, accepted work, pending reviews and skipped occurrences do not qualify.

## Local choices and pairing

The Sub can disable Tasks on the Tasks page. This choice remains on that device and stops task actions and attachment transfers. The existing locally consented Hardcore override can keep Tasks enabled. Re-enabling Tasks skips occurrences that expired during the disabled period rather than assessing them retroactively. Unpairing revokes further actions and transfers for that relationship; a new pairing has a separate identity and history.

Tasks and proof media remain on the paired devices. Both peers must support the Tasks capability; an older peer continues its existing sync without receiving task data. Offline actions are retained and acknowledged after reconnect. The Dom receives one private notification for each completion request when Android notification permission is available; task instructions and proof are omitted from notification copy.

## Limits

- One instruction attachment and up to five completion attachments, each at most 25 MiB. Supported formats are JPEG, PNG, WebP, GIF, MP3, Ogg, WAV, MP4 audio/video and WebM video.
- Attachments use a separate private store per pairing, bounded to 256 MiB and 512 attachments. Playback requires an available, fully received attachment; larger transfers resume across sync windows.
- The local journal is bounded to 250 task definitions and 20,000 actions per relationship. A full store rejects additional work without discarding existing history.
- Foreground deadlines are checked regularly. Background checks use Android scheduling, normally at least 15 minutes apart, and can be delayed by battery restrictions or a stopped app. A delayed check preserves the task's original deadline and any timely completion request.
