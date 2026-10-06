<script lang="ts">
	import { ArrowDown } from '@lucide/svelte';
	import { fmtTime, plural } from '$lib/format';
	import type { Lesson } from '$lib/types';
	import { lessonName, lessonState, untilText, type DayStats } from './lessons';

	// Строки под карточкой дня на «Расписании»: первая и последняя пара, окна, что сдать, и подсказка
	// (что идёт, что дальше). Отдельно – чтобы страница укладывалась в бюджет.
	let {
		items,
		stats,
		due,
		isToday,
		now
	}: { items: Lesson[]; stats: DayStats; due: number; isToday: boolean; now: number } = $props();

	/** Строка-подсказка дня: что идёт сейчас, что следующее, или тема первой пары. */
	const note = $derived.by((): { lead: string; text: string } | null => {
		if (isToday) {
			const cur = items.find((l) => !l.cancelled && lessonState(l, now) === 'now');
			if (cur)
				return {
					lead: lessonName(cur),
					text: `идёт до ${fmtTime(cur.endsAt)}${cur.place ? ` · ${cur.place}` : ''}`
				};
			const next = items.find((l) => !l.cancelled && l.startsAt > now);
			if (next)
				return {
					lead: lessonName(next),
					text: `${untilText(next.startsAt - now)}${next.place ? `, ${next.place}` : ''}`
				};
			if (items.length) return { lead: 'Пары на сегодня', text: 'закончились – отдыхайте' };
		}
		const topic = items.find((l) => l.note);
		if (topic) return { lead: lessonName(topic), text: topic.note };
		return null;
	});
</script>

<dl class="facts">
	{#if stats.first}
		<div>
			<dt>Первая пара</dt>
			<dd class="num">
				{fmtTime(stats.first.startsAt)}{stats.first.place ? ` · ${stats.first.place}` : ''}
			</dd>
		</div>
		<div>
			<dt>Последняя заканчивается</dt>
			<dd class="num">{fmtTime(stats.last!.endsAt)}</dd>
		</div>
		<div>
			<dt>Окна</dt>
			<dd class="num">
				{stats.gaps.length
					? stats.gaps.map((g) => `${fmtTime(g.from)}–${fmtTime(g.to)}`).join(', ')
					: 'без окон'}
			</dd>
		</div>
	{/if}
	<div>
		<dt>Сдать в этот день</dt>
		<dd>
			{#if due}
				<a href="#due"
					>{due}
					{plural(due, ['задание', 'задания', 'заданий'])}
					<ArrowDown size={14} /></a
				>
			{:else}
				ничего
			{/if}
		</dd>
	</div>
</dl>

{#if note}
	<p class="note"><strong>{note.lead}</strong> – {note.text}</p>
{/if}

<style>
	/* Строки «ключ – значение» под карточкой. */
	.facts {
		margin: var(--s3) 4px 0;
	}
	.facts div {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: var(--s3);
		padding: 11px 0;
		border-bottom: 1px solid var(--border);
	}
	.facts dt {
		color: var(--text-3);
	}
	.facts dd {
		margin: 0;
		font-weight: 600;
		text-align: right;
	}
	.facts a {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.note {
		margin: var(--s4) 4px 0;
		padding-left: 12px;
		border-left: 4px solid var(--accent);
		border-radius: 2px;
		color: var(--text-2);
	}
	.note strong {
		color: var(--text);
	}
</style>
