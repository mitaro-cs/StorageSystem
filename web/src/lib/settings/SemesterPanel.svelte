<script lang="ts">
	import { Archive, CalendarRange, Sparkles } from '@lucide/svelte';
	import type { MeGroup, Semester } from '$lib/types';
	import { offline } from '$lib/offline/engine';
	import { loadSemesters, renameSemester, restoreSemester } from '$lib/content/semesters';
	import Menu from '$lib/ui/Menu.svelte';
	import { put } from '$lib/api';
	import { fmtDate, plural } from '$lib/format';
	import { phase, sessionNavVisible } from '$lib/content/session';
	import { loadMe } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';

	let { group }: { group: MeGroup } = $props();

	let datesOpen = $state(false);
	let wizard = $state(false);
	let archiveOpen = $state(false);
	let semesters = $state<Semester[] | null>(null);
	const gid = $derived(group.id);

	async function loadArchives() {
		try {
			semesters = (await loadSemesters(gid)).items;
		} catch {
			semesters = semesters ?? [];
		}
	}
	$effect(() => {
		void gid;
		// Живые обновления: архив собрали или вернули на другом устройстве.
		void offline.version;
		loadArchives();
	});
	const p = $derived(phase(group.session, Date.now()));

	// Кнопка «Сессия» в меню нужна пару раз в год: староста решает, когда её показывать.
	type NavMode = NonNullable<MeGroup['sessionNav']>;
	const navModes: { value: NavMode; label: string }[] = [
		{ value: 'auto', label: 'Около сессии' },
		{ value: 'show', label: 'Всегда' },
		{ value: 'hide', label: 'Не показывать' }
	];
	const navMode = $derived<NavMode>(group.sessionNav ?? 'auto');
	const navVisible = $derived(sessionNavVisible(group, Date.now()));
	let navBusy = $state(false);

	async function pickNav(mode: NavMode) {
		if (navBusy || mode === navMode) return;
		navBusy = true;
		try {
			await put(`/api/groups/${group.id}/session-nav`, { mode });
			await loadMe();
			toast(
				mode === 'hide'
					? 'Кнопки «Сессия» в меню не будет'
					: mode === 'show'
						? 'Кнопка «Сессия» в меню – всегда'
						: 'Кнопка «Сессия» появится около сессии',
				'ok'
			);
		} catch (e) {
			toastError(e);
		} finally {
			navBusy = false;
		}
	}
</script>

<div class="stack">
	<section class="card block">
		<div class="row">
			<span class="ic"><CalendarRange size={20} /></span>
			<div class="spacer">
				<h3>Сессия</h3>
				{#if group.session}
					<p class="muted num">
						{fmtDate(group.session.from)} – {fmtDate(group.session.to)} ·
						{p.kind === 'before'
							? `через ${p.days} ${plural(p.days, ['день', 'дня', 'дней'])}`
							: p.kind === 'during'
								? `идёт, день ${p.day} из ${p.total}`
								: 'закончилась'}
					</p>
				{:else}
					<p class="muted">
						Даты не заданы – карточка сессии появится, только когда экзамен близко.
					</p>
				{/if}
			</div>
			<Button onclick={() => (datesOpen = true)}
				>{group.session ? 'Изменить' : 'Задать даты'}</Button
			>
		</div>
		<p class="hint">
			Зачёты и экзамены – это задания с типом «Зачёт» или «Экзамен». Их расписание, обратный отсчёт
			и отметки «сдано» – на странице <a href="/session">«Сессия»</a>.
		</p>
		<div class="nav-pick">
			<p class="label" id="session-nav-label">Кнопка «Сессия» в меню</p>
			<div class="seg" role="radiogroup" aria-labelledby="session-nav-label">
				{#each navModes as m (m.value)}
					<button
						type="button"
						role="radio"
						aria-checked={navMode === m.value}
						class:on={navMode === m.value}
						disabled={navBusy}
						onclick={() => pickNav(m.value)}>{m.label}</button
					>
				{/each}
			</div>
			<p class="hint">
				{navMode === 'auto'
					? `Появится за три недели до начала сессии и уйдёт после последнего дня${group.session ? '' : ' – когда будут заданы даты'}.`
					: navMode === 'show'
						? 'Видна всем участникам группы всегда.'
						: 'В меню её нет; страница открывается по ссылке выше и с главной, когда близко экзамен.'}
				{navVisible ? 'Сейчас кнопка в меню есть.' : 'Сейчас кнопки в меню нет.'}
			</p>
		</div>
	</section>

	<section class="card block">
		<div class="row">
			<span class="ic"><Sparkles size={20} /></span>
			<div class="spacer">
				<h3>Новый семестр</h3>
				<p class="muted">
					Закончившиеся предметы – в архив, новые – одним списком, даты следующей сессии. Пара минут
					вместо десятка окон.
				</p>
			</div>
			<Button variant="primary" onclick={() => (wizard = true)}>Начать</Button>
		</div>
	</section>

	<section class="card block">
		<div class="row">
			<span class="ic"><Archive size={20} /></span>
			<div class="spacer">
				<h3>Архивы семестров</h3>
				<p class="muted">
					Предметы прошлого семестра – вместе, под одним названием. Задания, материалы и тесты
					сохраняются, в текущих списках их нет.
				</p>
			</div>
			<Button onclick={() => (archiveOpen = true)}>Создать</Button>
		</div>
		{#if semesters?.length}
			<ul class="archives">
				{#each semesters as s (s.id)}
					<li>
						<a href="/subjects?archive=1">{s.name}</a>
						<span class="faint small num"
							>{s.subjects}
							{plural(s.subjects, ['предмет', 'предмета', 'предметов'])} ·
							{fmtDate(s.createdAt)}</span
						>
						<span class="menu">
							<Menu
								label="Действия с архивом"
								items={[
									{
										label: 'Переименовать',
										onclick: () => renameSemester(s, loadArchives)
									},
									{
										label: 'Вернуть предметы',
										onclick: () => restoreSemester(s, loadArchives)
									}
								]}
							/>
						</span>
					</li>
				{/each}
			</ul>
		{:else if semesters}
			<p class="hint">Архивов пока нет. «Новый семестр» соберёт его сам.</p>
		{/if}
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
{#if archiveOpen}
	{#await import('$lib/content/ArchiveSemester.svelte') then m}
		<m.default bind:open={archiveOpen} groupId={group.id} onsaved={loadArchives} />
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
	.archives {
		display: flex;
		flex-direction: column;
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.archives li {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: 2px 12px;
		padding: 10px 0;
		border-top: 1px solid var(--border);
	}
	.archives a {
		color: var(--text);
		font-weight: 600;
	}
	.archives .menu {
		margin-left: auto;
	}
	.nav-pick {
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding-top: var(--s3);
		border-top: 1px solid var(--border);
	}
	.nav-pick .label {
		margin: 0;
	}
	.nav-pick .hint {
		margin: 0;
	}
	.seg {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 4px;
		padding: 4px;
		border-radius: 14px;
		background: var(--surface-2);
	}
	.seg button {
		min-width: 0;
		height: 38px;
		padding: 0 6px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text-2);
		font: inherit;
		font-size: 14px;
		font-weight: 550;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
		transition:
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease);
	}
	.seg button.on {
		background: var(--surface);
		color: var(--text);
		box-shadow: 0 1px 3px rgb(16 18 24 / 0.1);
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
