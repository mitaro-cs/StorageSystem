<script lang="ts">
	import { Archive, Plus } from '@lucide/svelte';
	import type { Snippet } from 'svelte';
	import { fmtDate, plural } from '$lib/format';
	import type { Semester, Subject } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';
	import Menu from '$lib/ui/Menu.svelte';
	import {
		addArchivedSubject,
		createManualSemester,
		loadSemesters,
		renameSemester,
		restoreSemester
	} from './semesters';

	// «Предметы → Архив» по семестрам (0.9.7): каждый архив – своим разделом, без семестра – в конце.
	// С 1.0.2 архив можно заполнить и вручную: «Прошлый семестр» – пустой архив, «Добавить предмет» –
	// предмет сразу в него (дальше файлы и конспекты – на странице предмета).
	let {
		archived,
		groupId,
		canManage,
		card
	}: {
		archived: Subject[];
		groupId: number | null;
		canManage: boolean;
		card: Snippet<[Subject, number]>;
	} = $props();

	let semesters = $state<Semester[]>([]);
	let creating = $state(false);

	async function load() {
		if (groupId === null) return;
		try {
			semesters = (await loadSemesters(groupId)).items;
		} catch {
			/* без названий – одним списком */
		}
	}
	// Перечитать, когда архив изменился (вернули предмет, собрали новый архив – и на другом устройстве).
	const key = $derived(archived.map((s) => `${s.id}:${s.semester ?? ''}`).join());
	$effect(() => {
		void key;
		load();
	});

	const sections = $derived(
		semesters
			.map((sem) => ({ sem, list: archived.filter((s) => s.semester === sem.id) }))
			// Пустой архив видит тот, кто его заполняет.
			.filter((x) => x.list.length > 0 || canManage)
	);
	const loose = $derived(
		archived.filter((s) => !s.semester || !semesters.some((x) => x.id === s.semester))
	);
</script>

{#if canManage && groupId !== null}
	<div class="tools">
		<Button size="s" onclick={() => createManualSemester(groupId!, load)}
			><Plus size={15} /> Прошлый семестр</Button
		>
		<Button size="s" variant="ghost" onclick={() => (creating = true)}
			><Archive size={15} /> Отправить предметы в архив</Button
		>
	</div>
{/if}

{#each sections as { sem, list } (sem.id)}
	<section class="sem" aria-label={sem.name}>
		<header>
			<Archive size={16} />
			<h3>{sem.name}</h3>
			<span class="faint small"
				>{list.length}
				{plural(list.length, ['предмет', 'предмета', 'предметов'])} · в архиве с {fmtDate(
					sem.createdAt
				)}</span
			>
			{#if canManage}
				<span class="menu">
					<Button size="s" variant="ghost" onclick={() => addArchivedSubject(sem)}
						><Plus size={15} /> Добавить предмет</Button
					>
					<Menu
						label="Действия с архивом"
						items={[
							{
								label: 'Переименовать',
								onclick: () => renameSemester(sem, load)
							},
							{
								label: list.length ? 'Вернуть предметы' : 'Убрать пустой архив',
								onclick: () => restoreSemester({ ...sem, subjects: list.length }, load)
							}
						]}
					/>
				</span>
			{/if}
		</header>
		{#if list.length}
			<div class="grid">
				{#each list as s, i (s.id)}{@render card(s, i)}{/each}
			</div>
		{:else}
			<p class="faint small empty">
				Пока пусто. «Добавить предмет» – и загрузите в него файлы, конспекты и задания прошлого
				семестра.
			</p>
		{/if}
	</section>
{/each}

{#if loose.length}
	<section class="sem" aria-label="Без семестра">
		{#if sections.length}
			<header>
				<Archive size={16} />
				<h3>Без семестра</h3>
				<span class="faint small"
					>{loose.length} {plural(loose.length, ['предмет', 'предмета', 'предметов'])}</span
				>
			</header>
		{/if}
		<div class="grid">
			{#each loose as s, i (s.id)}{@render card(s, i)}{/each}
		</div>
	</section>
{/if}

{#if creating && groupId !== null}
	{#await import('./ArchiveSemester.svelte') then m}
		<m.default bind:open={creating} {groupId} onsaved={load} />
	{/await}
{/if}

<style>
	.tools {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		justify-content: flex-end;
		margin-bottom: var(--s3);
	}
	.sem + .sem {
		margin-top: var(--s5);
	}
	header {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: 4px 10px;
		margin-bottom: var(--s3);
		color: var(--text-2);
	}
	h3 {
		margin: 0;
		color: var(--text);
		font-size: 16px;
	}
	.menu {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		margin-left: auto;
	}
	.empty {
		margin: 0;
		padding: 14px 16px;
		border: 1px dashed var(--border-strong);
		border-radius: var(--r);
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
		gap: var(--s4);
	}
</style>
