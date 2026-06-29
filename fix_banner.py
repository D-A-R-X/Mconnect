with open('app/src/main/res/layout/view_home_header.xml', encoding='utf-8') as f:
    content = f.read()

start_marker = '            <!-- Guidelines -->'
end_marker = '            <!-- Dots indicator -->'
start_idx = content.find(start_marker)
end_idx = content.find(end_marker)

print(f'Start: {start_idx}, End: {end_idx}')

new_middle = '''            <!-- Guidelines -->
            <!-- Text boundary: 44% -->
            <androidx.constraintlayout.widget.Guideline
                android:id="@+id/guidelineFleetText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                app:layout_constraintGuide_percent="0.44" />

            <!-- Illustration start: 40% -->
            <androidx.constraintlayout.widget.Guideline
                android:id="@+id/guidelineFleetVan"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                app:layout_constraintGuide_percent="0.40" />

            <!-- Cards start: 71% -->
            <androidx.constraintlayout.widget.Guideline
                android:id="@+id/guidelineFleetCards"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                app:layout_constraintGuide_percent="0.71" />

            <!-- Cards - BEHIND (declared first) -->
            <LinearLayout
                android:id="@+id/fleetCardsContainer"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginEnd="10dp"
                android:orientation="vertical"
                app:layout_constraintBottom_toTopOf="@+id/fleetDotsContainer"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintStart_toEndOf="@id/guidelineFleetCards"
                app:layout_constraintTop_toTopOf="parent">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:background="@drawable/bg_fleet_card_white"
                    android:gravity="center_vertical"
                    android:layout_marginBottom="5dp"
                    android:orientation="horizontal"
                    android:padding="7dp">

                    <LinearLayout
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:orientation="vertical">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:fontFamily="@font/inter_regular"
                            android:text="Today\'s Trips"
                            android:textColor="#9CA3AF"
                            android:textSize="7.5sp" />

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="1dp"
                            android:fontFamily="@font/inter_bold"
                            android:text="12"
                            android:textColor="#111827"
                            android:textSize="16sp" />

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:fontFamily="@font/inter_regular"
                            android:text="Pending"
                            android:textColor="#9CA3AF"
                            android:textSize="7.5sp" />
                    </LinearLayout>

                    <FrameLayout
                        android:layout_width="26dp"
                        android:layout_height="26dp"
                        android:layout_marginStart="3dp"
                        android:background="@drawable/bg_circle_blue_light">

                        <ImageView
                            android:layout_width="14dp"
                            android:layout_height="14dp"
                            android:layout_gravity="center"
                            android:src="@drawable/ic_admin_tab_trips"
                            app:tint="#0B61CA" />
                    </FrameLayout>
                </LinearLayout>

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:background="@drawable/bg_fleet_card_white"
                    android:gravity="center_vertical"
                    android:orientation="horizontal"
                    android:padding="7dp">

                    <LinearLayout
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:orientation="vertical">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:fontFamily="@font/inter_regular"
                            android:text="Active Vehicles"
                            android:textColor="#9CA3AF"
                            android:textSize="7.5sp" />

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="1dp"
                            android:fontFamily="@font/inter_bold"
                            android:text="18"
                            android:textColor="#111827"
                            android:textSize="16sp" />

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:fontFamily="@font/inter_regular"
                            android:text="On the road"
                            android:textColor="#9CA3AF"
                            android:textSize="7.5sp" />
                    </LinearLayout>

                    <FrameLayout
                        android:layout_width="26dp"
                        android:layout_height="26dp"
                        android:layout_marginStart="3dp"
                        android:background="@drawable/bg_circle_green_light">

                        <ImageView
                            android:layout_width="14dp"
                            android:layout_height="14dp"
                            android:layout_gravity="center"
                            android:src="@drawable/ic_admin_tab_vehicles"
                            app:tint="#1BCA0B" />
                    </FrameLayout>
                </LinearLayout>
            </LinearLayout>

            <!-- Illustration: van+phone+cityscape - IN FRONT (declared last = highest z) -->
            <ImageView
                android:id="@+id/ivFleetVan"
                android:layout_width="0dp"
                android:layout_height="0dp"
                android:layout_marginTop="2dp"
                android:layout_marginBottom="14dp"
                android:scaleType="fitCenter"
                android:src="@drawable/fleet_banner_composite"
                app:layout_constraintBottom_toTopOf="@+id/fleetDotsContainer"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintStart_toEndOf="@id/guidelineFleetText"
                app:layout_constraintTop_toTopOf="parent" />

            '''

new_content = content[:start_idx] + new_middle + content[end_idx:]

with open('app/src/main/res/layout/view_home_header.xml', 'w', encoding='utf-8') as f:
    f.write(new_content)

print(f'Done. New length: {len(new_content)}')
