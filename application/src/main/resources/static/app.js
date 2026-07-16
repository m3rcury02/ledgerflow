// Mock Data
let payments = [
    { ref: 'ord_98231X', customer: 'cus_alp239', amount: 15000, currency: 'USD', status: 'COMPLETED', time: new Date(Date.now() - 1000 * 60 * 2) },
    { ref: 'ord_98230X', customer: 'cus_bet401', amount: 4500, currency: 'EUR', status: 'COMPLETED', time: new Date(Date.now() - 1000 * 60 * 15) },
    { ref: 'ord_98229X', customer: 'cus_gam881', amount: 120000, currency: 'USD', status: 'DECLINED', time: new Date(Date.now() - 1000 * 60 * 45) },
    { ref: 'ord_98228X', customer: 'cus_del112', amount: 8990, currency: 'GBP', status: 'COMPLETED', time: new Date(Date.now() - 1000 * 60 * 60) },
    { ref: 'ord_98227X', customer: 'cus_alp239', amount: 2500, currency: 'USD', status: 'COMPLETED', time: new Date(Date.now() - 1000 * 60 * 120) },
];

const formatMoney = (amount, currency) => {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount / 100);
};

const formatTime = (date) => {
    return date.toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
};

const renderTable = () => {
    const tbody = document.getElementById('paymentsBody');
    tbody.innerHTML = '';
    
    payments.sort((a,b) => b.time - a.time).forEach(p => {
        const tr = document.createElement('tr');
        
        let statusClass = 'status-pending';
        if (p.status === 'COMPLETED' || p.status === 'CREATED') statusClass = 'status-success';
        if (p.status === 'DECLINED' || p.status === 'FAILED') statusClass = 'status-failed';
        
        tr.innerHTML = `
            <td style="font-family: monospace; color: var(--accent);">${p.ref}</td>
            <td>${p.customer}</td>
            <td style="font-weight: 500;">${formatMoney(p.amount, p.currency)}</td>
            <td>${p.currency}</td>
            <td><span class="status-badge ${statusClass}">${p.status}</span></td>
            <td style="color: var(--text-secondary);">${formatTime(p.time)}</td>
        `;
        tbody.appendChild(tr);
    });
};

const showToast = (title, msg, type = 'success') => {
    const container = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.innerHTML = `
        <div class="toast-title">${title}</div>
        <div class="toast-msg">${msg}</div>
    `;
    container.appendChild(toast);
    
    // Trigger animation
    setTimeout(() => toast.classList.add('show'), 10);
    
    // Remove after 4s
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 300);
    }, 4000);
};

// Modal Logic
const modal = document.getElementById('paymentModal');
document.getElementById('btnCreatePayment').addEventListener('click', () => {
    // Generate a random default ref for convenience
    document.getElementById('refId').value = `ord_${Math.floor(Math.random()*1000000)}`;
    document.getElementById('customerId').value = `cus_${Math.floor(Math.random()*1000)}`;
    modal.classList.add('active');
});

const closeModal = () => modal.classList.remove('active');
document.getElementById('btnCloseModal').addEventListener('click', closeModal);
document.getElementById('btnCancelModal').addEventListener('click', closeModal);

// Form Submit
document.getElementById('paymentForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const ref = document.getElementById('refId').value;
    const customer = document.getElementById('customerId').value;
    const amount = parseInt(document.getElementById('amount').value, 10);
    const currency = document.getElementById('currency').value;
    
    const btn = document.getElementById('btnSubmitPayment');
    btn.textContent = 'Processing...';
    btn.style.opacity = '0.7';
    
    // Check if it's an exact idempotency duplicate in our mock state
    const existing = payments.find(p => p.ref === ref);
    
    // Real API integration would go here. For the demo, we simulate API latency.
    setTimeout(async () => {
        btn.textContent = 'Charge Customer';
        btn.style.opacity = '1';
        closeModal();
        
        if (existing) {
            showToast('Idempotency Key Reused', `Request with reference ${ref} was safely deduplicated.`, 'warning');
            // We do NOT add a new row, demonstrating idempotency.
        } else {
            // New payment
            payments.unshift({
                ref, customer, amount, currency, status: 'COMPLETED', time: new Date()
            });
            renderTable();
            showToast('Payment Succeeded', `${formatMoney(amount, currency)} charged to ${customer}`, 'success');
            
            // Animate active locks metric for realism
            const lockEl = document.getElementById('activeLocks');
            lockEl.textContent = '13';
            setTimeout(() => lockEl.textContent = '12', 1500);
        }
    }, 800);
});

// Initial Render
renderTable();
