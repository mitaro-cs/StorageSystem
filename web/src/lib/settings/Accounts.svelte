<script lang="ts">
	import { Printer, Copy, Download } from '@lucide/svelte';
	import { post } from '$lib/api';
	import { absolute, copy } from '$lib/copy';
	import { t } from '$lib/i18n/ru';
	import { can } from '$lib/session.svelte';
	import { toastError } from '$lib/toasts.svelte';
	import type { CreatedAccount, GroupRole } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';
	import QrCode from '$lib/ui/QrCode.svelte';
	import { parseNames } from './parseNames';

	interface Props {
		groupId: number;
		groupName: string;
		/** Внутри окна «Добавить людей»: без своей карточки и заголовка. */
		embedded?: boolean;
		oncreated?: () => void;
	}

	let { groupId, groupName, embedded = false, oncreated }: Props = $props();

	let text = $state('');
	let role = $state<GroupRole>('student');
	let delivery = $state<'LINK' | 'PASSWORD'>('LINK');
	let busy = $state(false);
	let result = $state<CreatedAccount[] | null>(null);

	const example = 'Петров Иван Сергеевич\nСмирнова Анна Олеговна\nКим Олег';
	const rows = $derived(parseNames(text));

	async function create(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		try {
			result = await post<CreatedAccount[]>(`/api/groups/${groupId}/accounts`, {
				accounts: rows,
				role,
				delivery
			});
			text = '';
			oncreated?.();
		} catch (err) {
			toastError(err);
		} finally {
			busy = false;
		}
	}

	const secret = (a: CreatedAccount) =>
		a.activationPath ? absolute(a.activationPath) : (a.temporaryPassword ?? '');

	function asText(): string {
		return (result ?? []).map((a) => `${a.displayName}\t${a.username}\t${secret(a)}`).join('\n');
	}

	function download() {
		const csv =
			'display_name,username,access\n' +
			(result ?? []).map((a) => `"${a.displayName}",${a.username},${secret(a)}`).join('\n');
		const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
		const link = document.createElement('a');
		link.href = url;
		link.download = `accounts-${groupName}.csv`;
		link.click();
		URL.revokeObjectURL(url);
	}
</script>

{#if result}
	<section class="sheet" class:card={!embedded} id="print-sheet">
		<div class="row no-print">
			<h2>Готово: {result.length}</h2>
			<span class="spacer"></span>
			<Button size="s" onclick={() => copy(asText(), 'Список скопирован')}
				><Copy size={15} /> Копировать</Button
			>
			<Button size="s" onclick={download}><Download size={15} /> CSV</Button>
			<Button size="s" onclick={() => print()}><Printer size={15} /> Печать</Button>
		</div>
		<p class="muted small no-print">
			Раздайте каждому его строку лично. {delivery === 'LINK'
				? 'Ссылки одноразовые и действуют 7 дней.'
				: 'При первом входе пароль нужно будет сменить.'}
			Этот лист больше не покажется.
		</p>
		<p class="print-only"><strong>{groupName}</strong> — доступ к сайту группы</p>
		<table>
			<thead>
				<tr>
					<th>ФИО</th>
					<th>Логин</th>
					<th>{delivery === 'LINK' ? 'Ссылка активации' : 'Временный пароль'}</th>
					{#if delivery === 'LINK'}<th class="qr-col">QR</th>{/if}
				</tr>
			</thead>
			<tbody>
				{#each result as a (a.userId)}
					<tr>
						<td>{a.displayName}</td>
						<td><code>{a.username}</code></td>
						<td class="secret"><code>{secret(a)}</code></td>
						{#if a.activationPath}
							<td class="qr-col"
								><span class="mini-qr"
									><QrCode value={secret(a)} label="QR: {a.displayName}" /></span
								></td
							>
						{/if}
					</tr>
				{/each}
			</tbody>
		</table>
		<div class="no-print">
			<Button variant="ghost" onclick={() => (result = null)}>Создать ещё</Button>
		</div>
	</section>
{:else}
	<form class="form" class:card={!embedded} class:bare={embedded} onsubmit={create}>
		{#if !embedded}<h2>Создать аккаунты</h2>{/if}
		<p class="muted small">
			Вставьте список: по одному ФИО на строку или CSV <code>username,ФИО</code>. Отчество — если
			есть. Логины без указания придумаются сами (Петров Иван → petrov.ivan).
		</p>
		<textarea
			class="textarea"
			rows="8"
			bind:value={text}
			placeholder={example}
			aria-label="Список людей"></textarea>
		<div class="grid">
			<div>
				<label class="label" for="acc-role">Роль</label>
				<select id="acc-role" class="select" bind:value={role}>
					<option value="student">{t.roles.student}</option>
					{#if can('assign_deputy', groupId)}<option value="deputy">{t.roles.deputy}</option>{/if}
					{#if can('assign_headman', groupId)}<option value="headman">{t.roles.headman}</option
						>{/if}
				</select>
			</div>
			<div>
				<label class="label" for="acc-delivery">Как выдать доступ</label>
				<select id="acc-delivery" class="select" bind:value={delivery}>
					<option value="LINK">Ссылка активации (человек сам задаёт пароль)</option>
					<option value="PASSWORD">Временный пароль</option>
				</select>
			</div>
		</div>
		<div class="row">
			<Button variant="primary" type="submit" loading={busy} disabled={rows.length === 0}>
				Создать {rows.length || ''}
			</Button>
			{#if rows.length}<span class="faint small"
					>Будет создано: {rows
						.map((r) => r.displayName)
						.slice(0, 3)
						.join(', ')}{rows.length > 3 ? '…' : ''}</span
				>{/if}
		</div>
	</form>
{/if}

<style>
	.form,
	.sheet {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		padding: var(--s5);
	}
	.grid {
		display: grid;
		grid-template-columns: 1fr 2fr;
		gap: var(--s3);
	}
	@media (max-width: 600px) {
		.grid {
			grid-template-columns: 1fr;
		}
	}
	.bare,
	.sheet:not(.card) {
		padding: 0;
	}
	.qr-col {
		width: 84px;
	}
	.mini-qr {
		display: block;
		width: 72px;
	}
	table {
		width: 100%;
		border-collapse: collapse;
		font-size: 13.5px;
	}
	th,
	td {
		text-align: left;
		padding: 8px 6px;
		border-bottom: 1px solid var(--border);
		vertical-align: top;
	}
	.secret code {
		word-break: break-all;
	}
	.print-only {
		display: none;
	}
	@media print {
		:global(body *) {
			visibility: hidden;
		}
		#print-sheet,
		#print-sheet :global(*) {
			visibility: visible;
		}
		#print-sheet {
			position: absolute;
			inset: 0;
			box-shadow: none;
		}
		.no-print {
			display: none !important;
		}
		.print-only {
			display: block;
		}
	}
</style>
