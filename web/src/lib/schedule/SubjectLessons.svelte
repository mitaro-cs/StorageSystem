<script lang="ts">
	import { get, qs } from '$lib/api';
	import { offline } from '$lib/offline/engine';
	import type { Lesson } from '$lib/types';
	import Empty from '$lib/ui/Empty.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import LessonCard from './LessonCard.svelte';

	// Вкладка «Пары» предмета: ближайшие сверху, прошедшие — ниже, свёрнутыми. У каждой — дата,
	// тема и сколько к ней заданий и материалов: весь курс по порядку.
	let { subjectId }: { subjectId: number } = $props();

	const DAY = 86_400_000;
	const SHOWN = 8;
	const now = Date.now();
	let list = $state<Lesson[] | null>(null);
	let all = $state(false);
	let showPast = $state(false);

	async function load() {
		try {
			list = await get<Lesson[]>(
				`/api/schedule${qs({ subject: subjectId, from: now - 200 * DAY, to: now + 200 * DAY })}`
			);
		} catch {
			list ??= [];
		}
	}

	$effect(() => {
		void subjectId;
		void offline.version;
		load();
	});

	const upcoming = $derived((list ?? []).filter((l) => l.endsAt > now));
	const past = $derived((list ?? []).filter((l) => l.endsAt <= now).reverse());
</script>

{#if !list}
	<Skeleton lines={4} />
{:else if list.length === 0}
	<div class="card">
		<Empty
			title="Пар в расписании нет"
			text="Когда староста загрузит расписание, пары предмета будут здесь — с темами, заданиями и материалами."
		/>
	</div>
{:else}
	<section class="block">
		<div class="section-head">
			<h2>Ближайшие пары</h2>
			<a class="more-link" href="/schedule">Всё расписание</a>
		</div>
		{#if upcoming.length}
			<div class="list">
				{#each all ? upcoming : upcoming.slice(0, SHOWN) as l (l.id)}<LessonCard
						lesson={l}
						{now}
						date
					/>{/each}
			</div>
			{#if upcoming.length > SHOWN && !all}
				<button class="pill more" onclick={() => (all = true)}
					>Показать все · <span class="num">{upcoming.length}</span></button
				>
			{/if}
		{:else}
			<p class="faint">Все пары в расписании уже прошли.</p>
		{/if}
	</section>
	{#if past.length}
		<section class="block">
			<button class="pill" aria-expanded={showPast} onclick={() => (showPast = !showPast)}
				>{showPast ? 'Скрыть прошедшие' : 'Прошедшие пары'} ·
				<span class="num">{past.length}</span></button
			>
			{#if showPast}
				<div class="list past">
					{#each past as l (l.id)}<LessonCard lesson={l} {now} date />{/each}
				</div>
			{/if}
		</section>
	{/if}
{/if}

<style>
	.block {
		margin-bottom: var(--s5);
	}
	.more {
		margin-top: var(--s3);
	}
	.past {
		margin-top: var(--s3);
	}
</style>
