<script lang="ts">
	import { ArrowRight, GraduationCap, MapPin } from '@lucide/svelte';
	import type { Homework } from '$lib/types';
	import { fmtDate, fmtTime, fmtWeekdayShort, plural } from '$lib/format';
	import { daysUntil, nextExam, phase, progress, type SessionDates } from './session';
	import { kindOf } from './kinds';

	// Сессия на главной: сколько дней до ближайшего зачёта или экзамена и сколько уже сдано.
	let {
		exams,
		dates,
		now = Date.now()
	}: { exams: Homework[]; dates: SessionDates | null; now?: number } = $props();

	const p = $derived(phase(dates, now));
	const next = $derived(nextExam(exams, now));
	const done = $derived(progress(exams));
	const left = $derived(next ? daysUntil(next.dueAt, now) : 0);
	const status = $derived(
		p.kind === 'before'
			? `начнётся через ${p.days} ${plural(p.days, ['день', 'дня', 'дней'])}`
			: p.kind === 'during'
				? `идёт · день ${p.day} из ${p.total}`
				: dates
					? 'закончилась'
					: ''
	);
	// Экзамен (м.р.) и зачёт (м.р.): «до экзамена», «до зачёта».
	const until = $derived(next ? (next.kind === 'exam' ? 'экзамена' : 'зачёта') : '');
</script>

<a class="session" href="/session">
	<span class="head">
		<span class="icon"><GraduationCap size={18} /></span>
		<strong>Сессия</strong>
		{#if status}<span class="status">{status}</span>{/if}
		<span class="go" aria-hidden="true"><ArrowRight size={17} /></span>
	</span>

	{#if next}
		<span class="count">
			{#if left === 0}
				<strong class="big">Сегодня</strong>
			{:else if left === 1}
				<strong class="big">Завтра</strong>
			{:else}
				<strong class="big num">{left}</strong>
				<span class="unit">{plural(left, ['день', 'дня', 'дней'])} до {until}</span>
			{/if}
		</span>
		<span class="what">
			<span class="kind">{kindOf(next.kind).label}</span>
			<span class="title">{next.subject.name}</span>
		</span>
		<span class="when num">
			{fmtWeekdayShort(next.dueAt)}, {fmtDate(next.dueAt, now)}, {fmtTime(next.dueAt)}
			{#if next.place}<span class="place"><MapPin size={13} /> {next.place}</span>{/if}
		</span>
	{:else if done.total}
		<span class="count"><strong class="big">Всё сдано</strong></span>
	{/if}

	{#if done.total}
		<span class="progress">
			<span class="bar"><span style:width="{(done.done / done.total) * 100}%"></span></span>
			<span class="num">сдано {done.done} из {done.total}</span>
		</span>
	{/if}
</a>

<style>
	.session {
		display: flex;
		flex-direction: column;
		gap: 10px;
		padding: var(--s4) var(--s5) var(--s5);
		border-radius: var(--r-xl);
		border: 1px solid var(--border);
		background:
			radial-gradient(120% 90% at 100% 0%, var(--surface-2) 0%, transparent 60%), var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-1);
		transition:
			transform var(--dur) var(--ease),
			box-shadow var(--dur) var(--ease);
	}
	.session:hover {
		text-decoration: none;
		transform: translateY(-2px);
		box-shadow: var(--shadow-2);
	}
	.session:active {
		transform: scale(0.99);
	}
	.head {
		display: flex;
		align-items: center;
		gap: 10px;
		min-width: 0;
	}
	.icon {
		display: grid;
		place-items: center;
		width: 32px;
		height: 32px;
		border-radius: 10px;
		background: var(--inverse);
		color: var(--inverse-text);
		flex: none;
	}
	.status {
		color: var(--text-2);
		font-size: 14px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.go {
		margin-left: auto;
		color: var(--text-3);
		flex: none;
	}
	.count {
		display: flex;
		align-items: baseline;
		gap: 10px;
		margin-top: 4px;
	}
	.big {
		font-size: 44px;
		line-height: 1;
		font-weight: 700;
		letter-spacing: -0.03em;
	}
	.unit {
		color: var(--text-2);
		font-size: 16px;
	}
	.what {
		display: flex;
		align-items: baseline;
		gap: 8px;
		min-width: 0;
		font-size: 17px;
		font-weight: 600;
	}
	.kind {
		flex: none;
		color: var(--text-2);
		font-weight: 500;
	}
	.title {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.when {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 4px 12px;
		color: var(--text-2);
		font-size: 14px;
	}
	.place {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.progress {
		display: flex;
		align-items: center;
		gap: 12px;
		margin-top: 4px;
		color: var(--text-2);
		font-size: 13px;
		white-space: nowrap;
	}
	.bar {
		flex: 1;
		height: 6px;
		border-radius: 3px;
		background: var(--surface-3);
		overflow: hidden;
	}
	.bar span {
		display: block;
		height: 100%;
		border-radius: 3px;
		background: var(--ok);
		transition: width 400ms var(--ease);
	}
</style>
