<script lang="ts">
	import { CalendarRange, Sparkles } from '@lucide/svelte';
	import type { MeGroup } from '$lib/types';
	import { fmtDate, plural } from '$lib/format';
	import { phase } from '$lib/content/session';
	import Button from '$lib/ui/Button.svelte';

	let { group }: { group: MeGroup } = $props();

	let datesOpen = $state(false);
	let wizard = $state(false);
	const p = $derived(phase(group.session, Date.now()));
</script>

<div class="stack">
	<section class="card block">
		<div class="row">
			<span class="ic"><CalendarRange size={20} /></span>
			<div class="spacer">
				<h3>Сессия</h3>
				{#if group.session}
					<p class="muted num">
						{fmtDate(group.session.from)} — {fmtDate(group.session.to)} ·
						{p.kind === 'before'
							? `через ${p.days} ${plural(p.days, ['день', 'дня', 'дней'])}`
							: p.kind === 'during'
								? `идёт, день ${p.day} из ${p.total}`
								: 'закончилась'}
					</p>
				{:else}
					<p class="muted">
						Даты не заданы — карточка сессии появится, только когда экзамен близко.
					</p>
				{/if}
			</div>
			<Button onclick={() => (datesOpen = true)}
				>{group.session ? 'Изменить' : 'Задать даты'}</Button
			>
		</div>
		<p class="hint">
			Зачёты и экзамены — это задания с типом «Зачёт» или «Экзамен». Их расписание, обратный отсчёт
			и отметки «сдано» — на странице <a href="/session">«Сессия»</a>.
		</p>
	</section>

	<section class="card block">
		<div class="row">
			<span class="ic"><Sparkles size={20} /></span>
			<div class="spacer">
				<h3>Новый семестр</h3>
				<p class="muted">
					Закончившиеся предметы — в архив, новые — одним списком, даты следующей сессии. Пара минут
					вместо десятка окон.
				</p>
			</div>
			<Button variant="primary" onclick={() => (wizard = true)}>Начать</Button>
		</div>
	</section>
</div>

{#if datesOpen}
	{#await import('$lib/content/SessionDates.svelte') then m}
		<m.default bind:open={datesOpen} groupId={group.id} dates={group.session} />
	{/await}
{/if}
{#if wizard}
	{#await import('$lib/content/NewSemester.svelte') then m}
		<m.default bind:open={wizard} {group} />
	{/await}
{/if}

<style>
	.block {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.row {
		align-items: flex-start;
		gap: var(--s3);
	}
	.ic {
		flex: none;
		display: grid;
		place-items: center;
		width: 40px;
		height: 40px;
		border-radius: 12px;
		background: var(--surface-2);
	}
	h3 {
		margin: 0 0 2px;
	}
	@media (max-width: 520px) {
		.row {
			flex-wrap: wrap;
		}
		.row :global(.btn) {
			width: 100%;
		}
	}
</style>
