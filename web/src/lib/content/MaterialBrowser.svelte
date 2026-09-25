<script lang="ts">
	import { offline } from '$lib/offline/engine';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { Folder as FolderIcon, FolderPlus, Plus, ChevronRight } from '@lucide/svelte';
	import { del, get, patch, post } from '$lib/api';
	import { fly, stagger } from '$lib/motion';
	import { toastError } from '$lib/toasts.svelte';
	import type { MaterialListing } from '$lib/types';
	import { materialActions } from './materialActions';
	import MaterialRow from './MaterialRow.svelte';
	import MaterialAdd from './MaterialAdd.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Menu from '$lib/ui/Menu.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import { ask, askText } from '$lib/ui/ask.svelte';

	interface Props {
		subjectId: number;
		subjectName: string;
		/** false — путь рисует страница (раздел «Файлы»), а сюда только сообщаем его. */
		showPath?: boolean;
		onpath?: (path: { id: number; name: string }[]) => void;
	}

	let { subjectId, subjectName, showPath = true, onpath }: Props = $props();

	let data = $state<MaterialListing | null>(null);
	let adding = $state(false);
	const folder = $derived(
		page.url.searchParams.get('folder') ? Number(page.url.searchParams.get('folder')) : null
	);

	async function load() {
		try {
			data = await get<MaterialListing>(
				`/api/subjects/${subjectId}/materials${folder ? `?folder=${folder}` : ''}`
			);
			onpath?.(data.path);
		} catch (e) {
			toastError(e);
		}
	}

	$effect(() => {
		void folder;
		void subjectId;
		void offline.version;
		load();
	});

	function open(id: number | null) {
		const url = new URL(page.url);
		if (url.pathname.startsWith('/subjects')) url.searchParams.set('tab', 'materials');
		if (id === null) url.searchParams.delete('folder');
		else url.searchParams.set('folder', String(id));
		goto(url.pathname + url.search, { noScroll: true, keepFocus: true });
	}

	async function newFolder() {
		const name = await askText('Как назвать папку?', {
			title: 'Новая папка',
			ok: 'Создать',
			maxlength: 80
		});
		if (!name) return;
		try {
			await post(`/api/subjects/${subjectId}/folders`, { name, parentId: folder });
			load();
		} catch (e) {
			toastError(e);
		}
	}

	async function renameFolder(id: number, current: string) {
		const name = await askText('Новое название папки', {
			title: 'Переименовать',
			ok: 'Сохранить',
			value: current,
			maxlength: 80
		});
		if (!name || name === current) return;
		try {
			await patch(`/api/folders/${id}`, { name });
			load();
		} catch (e) {
			toastError(e);
		}
	}

	async function deleteFolder(id: number, name: string) {
		if (
			!(await ask(`Удалить папку «${name}» со всем содержимым?`, { ok: 'Удалить', danger: true }))
		)
			return;
		try {
			await del(`/api/folders/${id}`);
			load();
		} catch (e) {
			toastError(e);
		}
	}
</script>

{#if !data}
	<Skeleton lines={5} />
{:else}
	<div class="bar">
		{#if showPath}
			<nav class="crumbs" aria-label="Путь">
				<button class:current={data.path.length === 0} onclick={() => open(null)}
					>{subjectName}</button
				>
				{#each data.path as c, i (c.id)}
					<ChevronRight size={14} />
					<button class:current={i === data.path.length - 1} onclick={() => open(c.id)}
						>{c.name}</button
					>
				{/each}
			</nav>
		{/if}
		<span class="spacer"></span>
		{#if data.canUpload}
			<Button size="s" variant="ghost" onclick={newFolder} label="Новая папка"
				><FolderPlus size={16} /></Button
			>
		{/if}
		{#if data.canUpload || data.canSuggest}
			<Button size="s" variant="primary" onclick={() => (adding = true)}
				><Plus size={16} /> {data.canUpload ? 'Добавить' : 'Предложить'}</Button
			>
		{/if}
	</div>

	{#if data.folders.length}
		<div class="folders">
			{#each data.folders as f, i (f.id)}
				<div class="folder" in:fly={{ y: 6, delay: stagger(i) }}>
					<button class="open" onclick={() => open(f.id)}>
						<FolderIcon size={20} />
						<span class="fname">{f.name}</span>
						<span class="faint small num">{f.count}</span>
					</button>
					{#if data.canUpload}
						<Menu
							items={[
								{ label: 'Переименовать', onclick: () => renameFolder(f.id, f.name) },
								{ label: 'Удалить', danger: true, onclick: () => deleteFolder(f.id, f.name) }
							]}
						/>
					{/if}
				</div>
			{/each}
		</div>
	{/if}

	{#if data.materials.length}
		<div class="list">
			{#each data.materials as m, i (m.id)}
				<div in:fly={{ y: 8, delay: stagger(i) }}>
					<MaterialRow {m} actions={materialActions(m, load)} />
				</div>
			{/each}
		</div>
	{:else if data.folders.length === 0}
		<div class="card">
			<Empty
				title="Здесь пока пусто"
				text={data.canUpload || data.canSuggest
					? 'Добавьте конспекты, методички или полезные ссылки.'
					: 'Когда староста добавит материалы, они появятся здесь.'}
			/>
		</div>
	{/if}

	<MaterialAdd
		bind:open={adding}
		{subjectId}
		folderId={folder}
		suggest={!data.canUpload}
		onsaved={load}
	/>
{/if}

<style>
	.bar {
		display: flex;
		align-items: center;
		gap: 8px;
		margin-bottom: var(--s3);
		flex-wrap: wrap;
	}
	.crumbs {
		display: flex;
		align-items: center;
		gap: 2px;
		flex-wrap: wrap;
		color: var(--text-3);
		min-width: 0;
	}
	.crumbs button {
		border: 0;
		background: none;
		padding: 4px 6px;
		border-radius: 6px;
		color: var(--text-2);
		font-weight: 550;
		font-size: 14px;
	}
	.crumbs button:hover {
		background: var(--surface-2);
		color: var(--text);
	}
	.crumbs .current {
		color: var(--text);
	}
	.folders {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
		gap: var(--s2);
		margin-bottom: var(--s3);
	}
	.folder {
		display: flex;
		align-items: center;
		background: var(--surface);
		border-radius: var(--r);
		box-shadow: var(--shadow-1);
		padding-right: 4px;
		transition: box-shadow var(--dur) var(--ease);
	}
	.folder:hover {
		box-shadow: var(--shadow-2);
	}
	.open {
		flex: 1;
		min-width: 0;
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 12px;
		border: 0;
		background: none;
		color: var(--amber);
		text-align: left;
	}
	.fname {
		flex: 1;
		min-width: 0;
		color: var(--text);
		font-weight: 550;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
</style>
