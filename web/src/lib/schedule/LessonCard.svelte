<script lang="ts">
	import { MapPin, NotebookPen, Paperclip } from '@lucide/svelte';
	import { fmtDate, fmtTime, fmtWeekdayShort } from '$lib/format';
	import type { Lesson } from '$lib/types';
	import { lessonKind, lessonName, lessonProgress, lessonState, untilText } from './lessons';

	// Пара: время слева, полоса цвета предмета, предмет, вид, аудитория, тема; справа — «идёт»,
	// «через 20 мин» и сколько к ней заданий и материалов. Вся строка — ссылка на пару.
	let {
		lesson,
		now = Date.now(),
		date = false
	}: {
		lesson: Lesson;
		now?: number;
		/** Показать и дату (список пар предмета за семестр). */
		date?: boolean;
	} = $props();

	const status = $derived(lessonState(lesson, now));
	const kind = $derived(lessonKind(lesson.kind));
</script>

<div class="lesson {status}" style:--subject={lesson.subject?.color ?? 'var(--text-3)'}>
	<div class="time num">
		{#if date}<span class="day"
				>{fmtWeekdayShort(lesson.startsAt)}, {fmtDate(lesson.startsAt, now)}</span
			>{/if}
		<strong>{fmtTime(lesson.startsAt)}</strong>
		<span class="end">{fmtTime(lesson.endsAt)}</span>
	</div>
	<span class="bar" aria-hidden="true"></span>
	<div class="main">
		<a class="name" href="/schedule/{lesson.id}">{lessonName(lesson)}</a>
		<div class="meta">
			{#if kind.short}<span class="kind">{kind.short}</span>{/if}
			{#if lesson.place}<span class="where"
					><MapPin size={13} /><span class="ellipsis">{lesson.place}</span></span
				>{/if}
			{#if lesson.teacher}<span class="who ellipsis">{lesson.teacher}</span>{/if}
		</div>
		{#if lesson.note}<p class="note">{lesson.note}</p>{/if}
	</div>
	<div class="side">
		{#if status === 'now'}<span class="chip live"><i class="dot"></i>идёт</span>
		{:else if status === 'soon'}<span class="chip soon num">{untilText(lesson.startsAt - now)}</span
			>{/if}
		{#if lesson.homework || lesson.materials}
			<span class="counts">
				{#if lesson.homework}<span title="Заданий к паре"
						><NotebookPen size={14} /><span class="num">{lesson.homework}</span></span
					>{/if}
				{#if lesson.materials}<span title="Материалов"
						><Paperclip size={14} /><span class="num">{lesson.materials}</span></span
					>{/if}
			</span>
		{/if}
	</div>
	{#if status === 'now'}<span class="progress" style:--p={lessonProgress(lesson, now)}></span>{/if}
</div>

<style>
	.lesson {
		position: relative;
		display: flex;
		align-items: center;
		gap: 12px;
		min-height: 72px;
		padding: 12px var(--s4);
		background: var(--surface);
		cursor: pointer;
		transition: background-color var(--dur) var(--ease);
	}
	.lesson:hover {
		background: color-mix(in srgb, var(--surface-2) 50%, var(--surface));
	}
	:global(:root:is([data-style='glass'], [data-style='depth'])) .lesson {
		background: transparent;
	}
	/* Нажатие в любом месте строки открывает пару. */
	.name::after {
		content: '';
		position: absolute;
		inset: 0;
		z-index: 1;
	}
	.name:focus-visible {
		outline: none;
	}
	.lesson:has(.name:focus-visible) {
		outline: 2px solid var(--focus);
		outline-offset: -2px;
	}
	.time {
		flex: none;
		display: flex;
		flex-direction: column;
		align-items: flex-end;
		width: 52px;
		line-height: 1.2;
	}
	.time strong {
		font-size: 17px;
		font-weight: 700;
		letter-spacing: -0.02em;
	}
	.end {
		color: var(--text-3);
		font-size: 13px;
	}
	.day {
		color: var(--text-2);
		font-size: 12px;
		white-space: nowrap;
	}
	.time:has(.day) {
		width: 86px;
	}
	.bar {
		flex: none;
		align-self: stretch;
		width: 4px;
		border-radius: 2px;
		background: var(--subject);
	}
	.main {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 3px;
	}
	.name {
		color: var(--text);
		font-size: 15.5px;
		font-weight: 620;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.name:hover {
		text-decoration: none;
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 4px 10px;
		min-width: 0;
		color: var(--text-2);
		font-size: 13px;
	}
	.kind {
		padding: 1px 8px;
		border-radius: var(--r-full);
		background: color-mix(in srgb, var(--subject) 16%, transparent);
		color: var(--text);
		font-weight: 600;
		font-size: 12px;
	}
	.where {
		display: inline-flex;
		align-items: center;
		gap: 3px;
		min-width: 0;
		max-width: 200px;
	}
	.who {
		max-width: 220px;
	}
	.ellipsis {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.note {
		color: var(--text-2);
		font-size: 13.5px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.side {
		flex: none;
		display: flex;
		flex-direction: column;
		align-items: flex-end;
		gap: 6px;
	}
	.counts {
		display: inline-flex;
		gap: 10px;
		color: var(--text-2);
		font-size: 13px;
	}
	.counts > span {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	/* «Идёт» — чернилами (читается при любом цвете предмета), точка — цвета предмета. */
	.chip.live {
		gap: 6px;
		background: var(--text);
		color: var(--bg);
		font-weight: 650;
	}
	.dot {
		width: 7px;
		height: 7px;
		border-radius: 50%;
		background: var(--subject);
		box-shadow: 0 0 0 2px color-mix(in srgb, var(--bg) 60%, transparent);
		animation: pulse 1.6s ease-in-out infinite;
	}
	@keyframes pulse {
		50% {
			transform: scale(0.6);
			opacity: 0.6;
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.dot {
			animation: none;
		}
	}
	.chip.soon {
		background: var(--amber-soft);
		color: var(--amber);
		font-weight: 600;
	}
	.past {
		opacity: 0.55;
	}
	.past:hover {
		opacity: 0.85;
	}
	.now {
		background: color-mix(in srgb, var(--subject) 7%, var(--surface));
	}
	.now .name {
		font-weight: 700;
	}
	.progress {
		position: absolute;
		left: 0;
		bottom: 0;
		width: calc(var(--p) * 100%);
		height: 3px;
		border-radius: 0 2px 2px 0;
		background: var(--subject);
	}
	@media (max-width: 420px) {
		.lesson {
			gap: 10px;
			padding: 12px;
		}
		.time {
			width: 44px;
		}
		.time:has(.day) {
			width: 70px;
		}
	}
</style>
